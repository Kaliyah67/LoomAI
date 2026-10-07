package com.lsq.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lsq.config.OssProperties;
import com.lsq.dto.request.EditImageRequest;
import com.lsq.dto.request.MergeImageRequest;
import com.lsq.dto.request.SearchImageRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsq.dto.TaskMessage;
import com.lsq.dto.response.*;
import com.lsq.entity.FileInfo;
import com.lsq.mapper.FileMapper;
import com.lsq.service.FileService;
import com.lsq.service.PythonAiIntegrationService;
import com.lsq.service.RecordService;
import com.lsq.service.TaskMessageProducer;
import com.lsq.utill.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
public class FileServiceImpl implements FileService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private OssProperties ossProperties;

    @Autowired
    private PythonAiIntegrationService pythonAiIntegrationService;

    @Autowired
    private RecordService recordService;

    @Autowired
    private TaskMessageProducer taskMessageProducer;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String TASK_KEY_PREFIX = "task:";
    private static final String USER_TASK_KEY_PREFIX = "task:user:";
    private static final Duration TASK_TTL = Duration.ofHours(1);

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileUploadResponse uploadImage(MultipartFile file, String authorization) {
        validateFile(file);

        // JWT鉴权 拿到userID
        String token = jwtUtil.parseToken(authorization);
        Long userId = jwtUtil.getUserId(token);

        // 生成 OSS 存储路径
        String fileName = file.getOriginalFilename();
        String extension = getFileExtension(fileName);
        String datePath = DATE_FORMATTER.format(LocalDate.now());
        String objectKey = "images/" + datePath + "/" + UUID.randomUUID() + extension;

        // 先落到系统临时文件，供 Python 审核；通过后再写入项目 uploads 目录并上传 OSS
        String suffix = StringUtils.hasText(extension) ? extension : ".tmp";
        Path tempPath = null;
        try {
            tempPath = Files.createTempFile("upload-", suffix);
            // 构建磁盘临时文件
            file.transferTo(tempPath.toFile());

            if (!pythonAiIntegrationService.validateImage(tempPath)) {
                throw new RuntimeException("图片只能是人物或衣服图片！");
            }
            // 审核通过 将图片保存到本机
            saveToLocalFromPath(tempPath, objectKey);
        } catch (IOException e) {
            log.error("准备或审核图片失败", e);
            throw new RuntimeException("图片处理失败");
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException e) {
                    log.warn("删除临时文件失败: {}", tempPath, e);
                }
            }
        }

        // 将文件保存到oss
        String ossUrl = uploadToOssFromLocal(objectKey);
        // 图片信息保存到mysql中
        saveFileRecord(userId, fileName, file.getSize(), ossUrl);
        // 图片通过CLIP向量化 向量登记到Redis
        pythonAiIntegrationService.registerUploadedImage(userId, ossUrl);

        // 返回结果
        FileUploadResponse response = new FileUploadResponse();
        response.setUrl(ossUrl);
        response.setFileName(fileName);
        response.setFileSize(file.getSize());
        return response;
    }

    @Override
    public List<FileInfo> myImages(String authorization) {
        String token = jwtUtil.parseToken(authorization);
        Long userId = jwtUtil.getUserId(token);
        LambdaQueryWrapper<FileInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FileInfo::getUserId, userId).orderByDesc(FileInfo::getId);
        return fileMapper.selectList(queryWrapper);
    }

    @Override
    public List<SearchImageResponse> search(String authorization, SearchImageRequest searchImageRequest) {
        // 1. 先去获取用户id
        String token = jwtUtil.parseToken(authorization);
        Long userId = jwtUtil.getUserId(token);
        List<FileInfo> imageFiles = myImages(authorization);
        Map<String, String> map = new HashMap<>();
        for (FileInfo imageFile : imageFiles) {
            map.put(imageFile.getOssUrl(), imageFile.getFileName());
        }
        // 2. 调用python服务
        List<SearchImageResponse> searchImageResponseList = pythonAiIntegrationService.search(userId, searchImageRequest);
        for (SearchImageResponse searchImageResponse : searchImageResponseList) {
            searchImageResponse.setFileName(map.get(searchImageResponse.getFilePath()));
        }
        // 3. 拿到数据，进行封装
        return searchImageResponseList;
    }

    @Override
    public EditImageResponse edit(String authorization, EditImageRequest editImageRequest) {
        // 1. 鉴权 -> 用户只能编辑自己上传的图片
        List<FileInfo> imageFiles = myImages(authorization);

        // 2. 判断当前图片的地址是否包含在 imageFile 的 ossUrl 字段的集合中
        List<String> urls = imageFiles.stream().map(FileInfo::getOssUrl).toList();
        if (!urls.contains(editImageRequest.getImage())){
            throw new RuntimeException("只能编辑自己上传的图片！");
        }

        // 3. 调用 python 服务，封装最好的返回结果
        EditImageResponse editImageResponse = pythonAiIntegrationService.edit(editImageRequest);

        // 新增调用记录
        Long userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));
        recordService.editSave(userId, editImageRequest, editImageResponse);

        return editImageResponse;
    }

    @Override
    public MergeImageResponse merge(String authorization, MergeImageRequest mergeImageRequest) {
        // 1. 鉴权 -> 用户只能合并自己上传的图片
        List<FileInfo> imageFiles = myImages(authorization);

        // 2. 判断上传的图片是否包含在imageFiles的ossUrl字段集合中
        List<String> urls =
                imageFiles.stream().map(FileInfo::getOssUrl).toList();
        if (!urls.contains(mergeImageRequest.getImage1()) ||
                !urls.contains(mergeImageRequest.getImage2())) {
            throw new RuntimeException("只能合并自己上传的图片");
        }

        // 3. 调用python服务
        MergeImageResponse mergeImageResponse = pythonAiIntegrationService.merge(mergeImageRequest);
        // 新增调用记录
        Long  userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));
        recordService.mergeSave(userId, mergeImageRequest, mergeImageResponse);
        return mergeImageResponse;
    }

    // 提交图片编辑异步任务
    @Override
    public TaskSubmitResponse submitEditAsync(String authorization, EditImageRequest request) {
        // 鉴权：根据userid 调取该用户上传的全部图片
        List<FileInfo> imageFiles = myImages(authorization);
        List<String> urls = imageFiles.stream().map(FileInfo::getOssUrl).toList();
        // 如果选择图片非用户图片 报错
        if (!urls.contains(request.getImage())) {
            throw new RuntimeException("只能编辑自己上传的图片！");
        }

        Long userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));
        String taskId = UUID.randomUUID().toString();

        // 构建消息
        Map<String, String> params = new HashMap<>();
        params.put("image", request.getImage());
        params.put("instruction", request.getInstruction());
        // 组装消息体
        TaskMessage message = new TaskMessage(taskId, userId, "EDIT", params);

        // 先：存入Redis（含任务输入信息）
        saveInitialTaskStatus(taskId, userId, "EDIT", request.getInstruction(), request.getImage(), null);
        // 后：发送消息到队列
        taskMessageProducer.sendEditTask(message);

        return new TaskSubmitResponse(taskId, "PENDING");
    }

    @Override
    public TaskSubmitResponse submitMergeAsync(String authorization, MergeImageRequest request) {
        List<FileInfo> imageFiles = myImages(authorization);
        List<String> urls = imageFiles.stream().map(FileInfo::getOssUrl).toList();
        if (!urls.contains(request.getImage1()) || !urls.contains(request.getImage2())) {
            throw new RuntimeException("只能合并自己上传的图片");
        }

        Long userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));
        String taskId = UUID.randomUUID().toString();

        Map<String, String> params = new HashMap<>();
        params.put("image1", request.getImage1());
        params.put("image2", request.getImage2());
        params.put("instruction", request.getInstruction());

        TaskMessage message = new TaskMessage(taskId, userId, "MERGE", params);
        saveInitialTaskStatus(taskId, userId, "MERGE", request.getInstruction(), request.getImage1(), request.getImage2());
        taskMessageProducer.sendMergeTask(message);

        return new TaskSubmitResponse(taskId, "PENDING");
    }

    @Override
    public TaskStatusResponse getTaskStatus(String taskId) {
        String json = stringRedisTemplate.opsForValue().get(TASK_KEY_PREFIX + taskId);
        if (json == null) {
            throw new RuntimeException("任务不存在或已过期");
        }
        try {
            return objectMapper.readValue(json, TaskStatusResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("解析任务状态失败", e);
        }
    }

    @Override
    public List<TaskStatusResponse> listMyTasks(String authorization, String action) {
        Long userId = jwtUtil.getUserId(jwtUtil.parseToken(authorization));
        String indexKey = USER_TASK_KEY_PREFIX + userId;
        // 按时间倒序取出该用户所有 taskId
        Set<String> taskIds = stringRedisTemplate.opsForZSet().reverseRange(indexKey, 0, -1);
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        List<TaskStatusResponse> result = new ArrayList<>();
        for (String taskId : taskIds) {
            String json = stringRedisTemplate.opsForValue().get(TASK_KEY_PREFIX + taskId);
            if (json == null) {
                continue; // 任务已过期
            }
            try {
                TaskStatusResponse status = objectMapper.readValue(json, TaskStatusResponse.class);
                if (StringUtils.hasText(action) && !action.equalsIgnoreCase(status.getType())) {
                    continue;
                }
                result.add(status);
            } catch (Exception e) {
                log.warn("解析任务状态失败, taskId={}", taskId, e);
            }
        }
        return result;
    }

    private void saveInitialTaskStatus(String taskId, Long userId, String type, String instruction, String inputImage, String inputImage2) {
        TaskStatusResponse status = new TaskStatusResponse();
        status.setTaskId(taskId);
        status.setStatus("PENDING");
        status.setType(type);
        status.setInstruction(instruction);
        status.setInputImage(inputImage);
        status.setInputImage2(inputImage2);
        status.setCreatedAt(System.currentTimeMillis());
        try {
            stringRedisTemplate.opsForValue()
                    .set(TASK_KEY_PREFIX + taskId, objectMapper.writeValueAsString(status), TASK_TTL);
            // 用户任务索引（ZSet，按时间倒序，便于「结果查询」按用户拉取任务列表）
            stringRedisTemplate.opsForZSet()
                    .add(USER_TASK_KEY_PREFIX + userId, taskId, System.currentTimeMillis());
            stringRedisTemplate.expire(USER_TASK_KEY_PREFIX + userId, TASK_TTL);
        } catch (JsonProcessingException e) {
            log.error("保存初始任务状态失败, taskId={}", taskId, e);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("图片文件不能为空");
        }
        if (ossProperties.getMaxSize() != null && file.getSize() > ossProperties.getMaxSize()) {
            throw new RuntimeException("图片大小超过限制");
        }
        String contentType = file.getContentType();
        if (!StringUtils.hasText(contentType) || !contentType.startsWith("image/")) {
            throw new RuntimeException("仅支持图片文件上传");
        }
    }

    private void saveToLocalFromPath(Path sourceFile, String objectKey) {
        try {
            Path targetPath = Paths.get(System.getProperty("user.dir"), "uploads", objectKey);
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(sourceFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("图片保存到本地失败", e);
            throw new RuntimeException("图片保存到系统失败");
        }
    }

    private String uploadToOssFromLocal(String objectKey) {
        Path localPath = Paths.get(System.getProperty("user.dir"), "uploads", objectKey);
        OSS ossClient = new OSSClientBuilder().build(
                "https://" + ossProperties.getEndpoint(),
                ossProperties.getAccessKeyId(),
                ossProperties.getAccessKeySecret()
        );
        try (InputStream inputStream = Files.newInputStream(localPath)) {
            ossClient.putObject(ossProperties.getBucketName(), objectKey, inputStream);
            return "https://" + ossProperties.getBucketName() + "." + ossProperties.getEndpoint() + "/" + objectKey;
        } catch (Exception e) {
            log.error("图片上传OSS失败", e);
            throw new RuntimeException("图片上传到OSS失败");
        } finally {
            ossClient.shutdown();
        }
    }

    private void saveFileRecord(Long userId, String fileName, Long fileSize, String ossUrl) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setUserId(userId);
        fileInfo.setFileName(fileName);
        fileInfo.setFileSize(fileSize);
        fileInfo.setOssUrl(ossUrl);
        fileMapper.insert(fileInfo);
    }

    private String getFileExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int index = fileName.lastIndexOf(".");
        if (index < 0) {
            return "";
        }
        return fileName.substring(index);
    }
}

package com.lsq.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.lsq.config.OssProperties;
import com.lsq.config.PythonAiServiceProperties;
import com.lsq.dto.python.PythonUploadImageRequest;
import com.lsq.dto.python.PythonUploadImageResponse;
import com.lsq.dto.request.EditImageRequest;
import com.lsq.dto.request.MergeImageRequest;
import com.lsq.dto.request.SearchImageRequest;
import com.lsq.dto.response.EditImageResponse;
import com.lsq.dto.response.MergeImageResponse;
import com.lsq.dto.response.SearchImageResponse;
import com.lsq.service.PythonAiIntegrationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Service
@Slf4j
public class PythonAiIntegrationServiceImpl implements PythonAiIntegrationService {
    
    // 定义python接口url
    private static final String UPLOAD_IMAGE_PATH = "/api/upload-image";
    private static final String VALIDATE_IMAGE_PATH = "/api/validate-image";
    private static final String SEARCH_IMAGE_PATH = "/api/search-image";
    private static final String SKILL_IMAGE_PATH = "/api/skill/image";

    private final PythonAiServiceProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final OssProperties ossProperties;

    public PythonAiIntegrationServiceImpl(
            PythonAiServiceProperties properties,
            ObjectMapper objectMapper,
            OssProperties ossProperties) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.ossProperties = ossProperties;
        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .build();
    }

    @Override
    public Optional<PythonUploadImageResponse> registerUploadedImage(Long userId, String ossUrl) {
        if (!properties.isEnabled()) {
            log.debug("Python AI 集成已关闭，跳过 registerUploadedImage");
            return Optional.empty();
        }
        if (userId == null || !StringUtils.hasText(ossUrl)) {
            log.warn("registerUploadedImage 参数无效: userId={}, ossUrl 为空={}", userId, !StringUtils.hasText(ossUrl));
            return Optional.empty();
        }
        if (!StringUtils.hasText(properties.getBaseUrl())) {
            log.warn("python.ai.base-url 未配置，跳过 registerUploadedImage");
            return Optional.empty();
        }

        PythonUploadImageRequest body = new PythonUploadImageRequest(ossUrl, userId);
        try {
            ResponseEntity<PythonUploadImageResponse> entity = restClient.post()
                    .uri(UPLOAD_IMAGE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(PythonUploadImageResponse.class);

            if (!entity.getStatusCode().is2xxSuccessful()) {
                log.warn("Python /api/upload-image 非成功状态: {}", entity.getStatusCode());
                return Optional.empty();
            }
            PythonUploadImageResponse resp = entity.getBody();
            if (resp == null) {
                log.warn("Python /api/upload-image 响应体为空");
                return Optional.empty();
            }
            if (!Boolean.TRUE.equals(resp.getSuccess())) {
                log.warn("Python /api/upload-image 返回 success=false, userId={}", userId);
                return Optional.empty();
            }
            log.info("Python 图片登记成功, imageId={}, userId={}", resp.getImageId(), userId);
            return Optional.of(resp);
        } catch (RestClientException e) {
            log.warn("调用 Python /api/upload-image 失败, userId={}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean validateImage(Path filePath) {
        if (filePath == null) {
            return false;
        }
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(filePath.toFile()));

            // 构建python Flask调用json
            // 利用springBoot提供的RestClient构造
            ResponseEntity<String> response = restClient.post()          // 这是一个POST请求
                    .uri(VALIDATE_IMAGE_PATH)                            // 接口url是什么
                    .body(body)                                          // 请求体
                    .retrieve()                                          // 执行
                    .toEntity(String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return false;
            }
            return objectMapper.readTree(response.getBody()).path("allow").asBoolean(false);
        } catch (Exception e) {
            log.warn("validateImage 调用失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<SearchImageResponse> search(Long userId, SearchImageRequest searchImageRequest) {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getBaseUrl())) {
            return List.of();
        }
        if (userId == null || searchImageRequest == null) {
            return List.of();
        }

        String query = searchImageRequest.getQuery();
        MultipartFile file = searchImageRequest.getFile();

        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("userId", userId);
            if (query != null && !query.isEmpty()) {
                body.add("query", query);
            }

            if (file != null) {
                byte[] fileBytes = file.getBytes();
                String fileName = file.getOriginalFilename();
                ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }
                };
                body.add("file", fileResource);
            }

            ResponseEntity<String> response = restClient.post()
                    .uri(SEARCH_IMAGE_PATH)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return List.of();
            }

            JsonNode result = objectMapper.readTree(response.getBody()).path("data");
            List<SearchImageResponse> searchImageResponseList = new ArrayList<>();
            for (JsonNode node : result) {
                SearchImageResponse searchImageResponse = new SearchImageResponse();
                searchImageResponse.setFilePath(node.path("filePath").asText(""));
                searchImageResponseList.add(searchImageResponse);
            }
            return searchImageResponseList;
        } catch (Exception e) {
            log.error("调用 Python 搜索图片接口失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public EditImageResponse edit(EditImageRequest editImageRequest) {
        // 1. 考虑转换：oss url -> file资源
        String sourceImageUrl = editImageRequest.getImage();
        String instruction = editImageRequest.getInstruction();
        Path sourceTemp = null;
        Path editTemp = null;

        try {
            sourceTemp = downloadToTempFile(sourceImageUrl);

            // 2. 构造请求参数访问 python 服务的编辑图片接口
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(sourceTemp.toFile()));
            body.add("instruction", instruction);

            ResponseEntity<String> response = restClient.post()
                    .uri(SKILL_IMAGE_PATH)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Python 编辑图片接口调用失败");
            }

            // 3. 拿到 Python 服务返回的响应之后需要处理数据
            JsonNode result = objectMapper.readTree(response.getBody());
            //
            String pythonUrl = result.path("url").asText("");
            // 4. 图片地址需要二次保存
            // 4.1 下载结果图片到本地
            editTemp = downloadToTempFile(pythonUrl);
            String contentType = Files.probeContentType(editTemp);
            String extension = ".png";
            // 4.2 二次上传到OSS url 持久化
            String objectKey = "image/edited" + UUID.randomUUID().toString().replace("-", "") + extension;
            String saveUrl = uploadEditedFileToOss(objectKey, editTemp, contentType);

            // 5. 封装一个返回对象
            EditImageResponse editImageResponse = new EditImageResponse();
            editImageResponse.setUrl(pythonUrl);
            editImageResponse.setSaveUrl(saveUrl);
            return editImageResponse;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (RestClientException e) {
            throw new RuntimeException(e);
        } finally {
            // 6. 无论成功与否，删掉临时文件
            try {
                if (sourceTemp != null) {
                    Files.deleteIfExists(sourceTemp);
                }
                if (editTemp != null) {
                    Files.deleteIfExists(editTemp);
                }
            } catch (IOException e) {
                log.error("删除临时文件失败", e);
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public MergeImageResponse merge(MergeImageRequest mergeImageRequest) {
        // 1. 文件转换： oss url -> file 资源
        String source1ImageUrl = mergeImageRequest.getImage1();
        String source2ImageUrl = mergeImageRequest.getImage2();
        String instruction = mergeImageRequest.getInstruction();
        Path source1Temp = null;
        Path source2Temp = null;
        Path mergeTemp = null;

        try {
            source1Temp = downloadToTempFile(source1ImageUrl);
            source2Temp = downloadToTempFile(source2ImageUrl);

            // 2. 构造请求参数去访问 python 服务合并图片接口
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file1", new FileSystemResource(source1Temp.toFile()));
            body.add("file2", new FileSystemResource(source2Temp.toFile()));
            body.add("instruction", instruction);

            ResponseEntity<String> response = restClient.post()
                    .uri(SKILL_IMAGE_PATH)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Python 合并图片接口调用失败");
            }

            // 3. 拿到 python 服务返回的响应
            JsonNode result = objectMapper.readTree(response.getBody());
            String pythonUrl = result.path("url").asText("");
            // 4. 拿到的图片进行二次保存到 oss
            mergeTemp = downloadToTempFile(pythonUrl);
            String contentType = Files.probeContentType(mergeTemp);
            String extension = ".png";
            String objectKey = "image/merged" + UUID.randomUUID().toString().replace("-", "") + extension;
            String saveUrl = uploadEditedFileToOss(objectKey, mergeTemp, contentType);
            // 5. 封装一个返回的对象
            MergeImageResponse mergeImageResponse = new MergeImageResponse();
            mergeImageResponse.setUrl(pythonUrl);
            mergeImageResponse.setSaveUrl(saveUrl);
            return mergeImageResponse;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (RestClientException e) {
            throw new RuntimeException(e);
        } finally {
            // 6. 删掉临时文件
            try {
                if (source1Temp != null) {
                    Files.deleteIfExists(source1Temp);
                }
                if (source2Temp != null) {
                    Files.deleteIfExists(source2Temp);
                }
                if (mergeTemp != null) {
                    Files.deleteIfExists(mergeTemp);
                }
            } catch (IOException e) {
                log.error("删除临时文件失败", e);
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 与课程 OssService.upload(objectKey, file, contentType) 等价：把本地文件传到阿里云 OSS。
     */
    private String uploadEditedFileToOss(String objectKey, Path localFile, String contentType) {
        OSS ossClient = new OSSClientBuilder().build(
                "https://" + ossProperties.getEndpoint(),
                ossProperties.getAccessKeyId(),
                ossProperties.getAccessKeySecret()
        );
        try (InputStream inputStream = Files.newInputStream(localFile)) {
            if (StringUtils.hasText(contentType)) {
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentType(contentType);
                ossClient.putObject(ossProperties.getBucketName(), objectKey, inputStream, metadata);
            } else {
                ossClient.putObject(ossProperties.getBucketName(), objectKey, inputStream);
            }
            return "https://" + ossProperties.getBucketName() + "." + ossProperties.getEndpoint() + "/" + objectKey;
        } catch (Exception e) {
            log.error("编辑结果上传 OSS 失败", e);
            throw new RuntimeException("图片上传到OSS失败");
        } finally {
            ossClient.shutdown();
        }
    }

    // 下载远程地址的url文件
    private Path downloadToTempFile(String fileUrl) {
        try {
            URL url = URI.create(fileUrl).toURL();
            Path tempFile = Files.createTempFile("image-edit-", ".tmp");
            try (InputStream inputStream = url.openStream()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String trimTrailingSlash(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return baseUrl;
        }
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}

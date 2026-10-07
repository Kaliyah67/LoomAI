package com.lsq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lsq.entity.FileInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileMapper extends BaseMapper<FileInfo> {
}

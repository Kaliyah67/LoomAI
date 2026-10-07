package com.lsq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lsq.entity.Record;
import org.apache.ibatis.annotations.Mapper;

// 调用记录的 Mapper
@Mapper
public interface RecordMapper extends BaseMapper<Record> {
}

package com.hermes.push.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SqlAuditMapper extends BaseMapper<SqlAudit> {
}

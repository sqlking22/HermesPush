package com.hermes.push.exec;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExecArtifactMapper extends BaseMapper<TaskExecArtifact> {

  int upsertInline(@Param("execId") long execId,
                   @Param("artifactKey") String artifactKey,
                   @Param("type") String type,
                   @Param("content") String content,
                   @Param("rowsCount") Integer rowsCount,
                   @Param("bytes") Integer bytes,
                   @Param("costMs") Long costMs);

  int upsertError(@Param("execId") long execId,
                  @Param("artifactKey") String artifactKey,
                  @Param("type") String type,
                  @Param("errorCode") String errorCode,
                  @Param("errorMsg") String errorMsg);
}

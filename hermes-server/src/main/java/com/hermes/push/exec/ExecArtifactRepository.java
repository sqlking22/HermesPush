package com.hermes.push.exec;

import org.springframework.stereotype.Repository;

/**
 * 执行产物仓储——基于 uk_art(exec_id, artifact_key) 的 upsert。
 */
@Repository
public class ExecArtifactRepository {

  private final ExecArtifactMapper mapper;

  public ExecArtifactRepository(ExecArtifactMapper mapper) {
    this.mapper = mapper;
  }

  public void upsertInline(long execId, String artifactKey, String type,
                           String content, int rowsCount, int bytes, long costMs) {
    mapper.upsertInline(execId, artifactKey, type, content, rowsCount, bytes, costMs);
  }

  public void upsertError(long execId, String artifactKey, String type,
                          String errorCode, String errorMsg) {
    mapper.upsertError(execId, artifactKey, type, errorCode, errorMsg);
  }
}

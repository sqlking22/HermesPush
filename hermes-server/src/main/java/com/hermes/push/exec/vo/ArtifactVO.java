package com.hermes.push.exec.vo;

public record ArtifactVO(
    String artifactKey,
    String type,
    String renderProvider,
    Integer rowsCount,
    Integer bytes,
    String content,
    String storageUri,
    Long costMs,
    String errorCode,
    String errorMsg) {
}

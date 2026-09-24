package com.hermes.push.exec.vo;

import java.time.LocalDateTime;

public record PushVO(
    Long channelId,
    String channelName,
    String artifactKey,
    String msgType,
    String status,
    Integer retryCount,
    String errorCode,
    String errorMsg,
    LocalDateTime sentAt) {
}

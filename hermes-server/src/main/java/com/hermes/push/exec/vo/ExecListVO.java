package com.hermes.push.exec.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ExecListVO(
    Long id,
    Long taskId,
    String taskName,
    String triggerType,
    String status,
    LocalDateTime fireTime,
    LocalDate bizDate,
    Integer rowsTotal,
    Long costMs,
    String errorCode,
    Integer ver,
    String nodeId) {
}

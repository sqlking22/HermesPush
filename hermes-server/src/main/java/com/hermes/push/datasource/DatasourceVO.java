package com.hermes.push.datasource;

public record DatasourceVO(
    Long id,
    String name,
    String type,
    String jdbcUrl,
    String username,
    boolean hasPassword,
    boolean roConfirmed,
    String status,
    Integer maxRows,
    Integer queryTimeoutSec,
    Integer poolMax,
    String overflowPolicy,
    String createdBy,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt
) {
  public static DatasourceVO from(Datasource d) {
    return new DatasourceVO(
        d.getId(),
        d.getName(),
        d.getType(),
        d.getJdbcUrl(),
        d.getUsername(),
        d.getPasswordCipher() != null && !d.getPasswordCipher().isBlank(),
        d.getRoConfirmed() != null && d.getRoConfirmed() == 1,
        d.getStatus(),
        d.getMaxRows(),
        d.getQueryTimeoutSec(),
        d.getPoolMax(),
        d.getOverflowPolicy(),
        d.getCreatedBy(),
        d.getCreatedAt(),
        d.getUpdatedAt()
    );
  }
}

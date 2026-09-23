package com.hermes.push.datasource;

import jakarta.validation.constraints.NotBlank;

public record DatasourceSaveRequest(
    @NotBlank String name,
    @NotBlank String type,
    @NotBlank String jdbcUrl,
    @NotBlank String username,
    String password,
    boolean roConfirmed,
    Integer maxRows,
    Integer queryTimeoutSec,
    Integer poolMax,
    String overflowPolicy
) {}

package com.hermes.push.datasource;

import jakarta.validation.constraints.NotBlank;

public record TestInlineRequest(
    @NotBlank String jdbcUrl,
    @NotBlank String username,
    String password
) {}

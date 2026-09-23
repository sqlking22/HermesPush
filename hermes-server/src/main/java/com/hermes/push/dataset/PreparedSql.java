package com.hermes.push.dataset;

import java.util.List;
import java.util.Map;

public record PreparedSql(
    String sql,
    List<Object> bindValues,
    Map<String, String> auditParams) {}

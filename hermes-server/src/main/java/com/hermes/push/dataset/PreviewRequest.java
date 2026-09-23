package com.hermes.push.dataset;

import java.util.Map;

public record PreviewRequest(String sql, Map<String, String> params) {}

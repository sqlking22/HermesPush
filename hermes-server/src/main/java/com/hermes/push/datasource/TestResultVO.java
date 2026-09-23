package com.hermes.push.datasource;

public record TestResultVO(boolean ok, String dbVersion, long costMs, String errorCode, String userMessage) {}

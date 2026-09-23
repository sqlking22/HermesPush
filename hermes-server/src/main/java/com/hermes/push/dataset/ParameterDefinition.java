package com.hermes.push.dataset;

public record ParameterDefinition(
    String name,
    ParamType type,
    boolean required,
    String defaultValue,
    boolean allowTextSubstitution,
    String whitelistPattern) {}

package com.hermes.push.channel;

public record DecryptedChannel(String type, String webhook, int rateLimit, int waitTimeout) {}

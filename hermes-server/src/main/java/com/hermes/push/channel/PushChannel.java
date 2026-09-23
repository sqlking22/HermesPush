package com.hermes.push.channel;

public interface PushChannel {
  String type();
  PushResult send(String webhookUrl, PushMessage msg, int rateLimitPerMin, int queueWaitTimeoutSec);
}

package com.hermes.push.task;

public interface ScheduleSyncPort {
  void onPublish(Long taskId, String cron);
  void onOffline(Long taskId);
  void onCronChange(Long taskId, String cron);
}

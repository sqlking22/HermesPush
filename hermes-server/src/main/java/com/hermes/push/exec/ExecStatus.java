package com.hermes.push.exec;

public enum ExecStatus {
  PENDING,
  RUNNING,
  RETRY_WAIT,
  SUCCESS,
  PARTIAL_SUCCESS,
  FAILED,
  TIMEOUT,
  CANCELLED
}

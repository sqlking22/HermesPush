package com.hermes.push.exec;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker 执行器：ApplicationReadyEvent 后启动 N 个虚拟线程循环 claim→run。
 * <p>每执行启动 30s 心跳定时，心跳失败时置中断标志，流水线在阶段边界检查并放弃。
 * <p>concurrency=0 时不启动（测试基类依赖此开关）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecWorker {

  private final ExecQueueRepository queue;
  private final ExecPipeline pipeline;

  @Value("${hermes.worker.concurrency:4}")
  private int concurrency;

  @Value("${hermes.worker.poll-interval-ms:2000}")
  private long pollIntervalMs;

  @Value("${hermes.node-id:node-dev}")
  private String nodeId;

  private final AtomicBoolean running = new AtomicBoolean(false);

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (concurrency <= 0) {
      log.info("ExecWorker disabled (concurrency={})", concurrency);
      return;
    }
    if (!running.compareAndSet(false, true)) return;
    log.info("ExecWorker starting, concurrency={}, nodeId={}", concurrency, nodeId);
    for (int i = 0; i < concurrency; i++) {
      Thread.ofVirtual().name("exec-worker-" + i).start(this::workerLoop);
    }
  }

  private void workerLoop() {
    while (running.get()) {
      try {
        var claimed = queue.claim(nodeId);
        if (claimed.isPresent()) {
          long execId = claimed.get();
          TaskExec exec = queue.getById(execId);
          if (exec == null) continue;
          ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = Thread.ofVirtual().unstarted(r);
                t.setName("heartbeat-" + execId);
                return t;
              });
          Thread execThread = Thread.currentThread();
          ScheduledFuture<?> hbFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
              boolean ok = queue.heartbeat(execId, nodeId);
              if (!ok) {
                log.warn("exec {} heartbeat failed, interrupting exec thread", execId);
                execThread.interrupt();
              }
            } catch (Exception e) {
              log.error("exec {} heartbeat error", execId, e);
            }
          }, 30, 30, TimeUnit.SECONDS);
          try {
            pipeline.run(exec);
          } finally {
            hbFuture.cancel(false);
            heartbeatScheduler.shutdownNow();
          }
        } else {
          // 空转休眠
          Thread.sleep(pollIntervalMs);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        log.error("worker loop error", e);
        try {
          Thread.sleep(pollIntervalMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }
}

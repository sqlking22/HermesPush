package com.hermes.push.exec;

import com.hermes.push.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecQueueRepositoryTest extends AbstractIntegrationTest {
  @Autowired ExecQueueRepository queue;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach void clean() { jdbc.update("TRUNCATE TABLE hp_task_exec"); }

  Long pending(int priority, String idem) {
    return queue.insertPending(1L, 1L, TriggerType.CRON, priority,
        LocalDateTime.now().minusMinutes(1), LocalDate.now(), "{}", idem);
  }

  @Test void claimReturnsHighestPriorityFirst() {
    Long low = pending(40, "c1");
    Long high = pending(70, "c2");
    assertThat(queue.claim("node-a")).contains(high);
    assertThat(queue.claim("node-a")).contains(low);
    assertThat(queue.claim("node-a")).isEmpty();
  }
  @Test void concurrentClaimNoDuplicate() throws Exception {
    for (int i = 0; i < 6; i++) pending(40, "cc" + i);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    AtomicInteger claimed = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    var futures = new java.util.ArrayList<Future<?>>();
    for (int t = 0; t < 3; t++) futures.add(pool.submit(() -> {
      try { start.await(); } catch (InterruptedException ignored) {}
      while (true) {
        try {
          if (queue.claim("node-" + Thread.currentThread().threadId()).isPresent()) {
            claimed.incrementAndGet();
          } else {
            break;
          }
        } catch (DeadlockLoserDataAccessException e) {
          // 并发 claim 偶发死锁（gap lock × 多线程 SELECT+UPDATE），重试一次即可
          try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
      }
    }));
    start.countDown();
    for (var f : futures) f.get(30, TimeUnit.SECONDS);
    pool.shutdown();
    assertThat(claimed.get()).isEqualTo(6);
    Integer running = jdbc.queryForObject("SELECT COUNT(*) FROM hp_task_exec WHERE status='RUNNING'", Integer.class);
    assertThat(running).isEqualTo(6);
  }
  @Test void heartbeatFailsForWrongNode() {
    Long id = pending(40, "hb1");
    queue.claim("node-x");
    assertThat(queue.heartbeat(id, "node-x")).isTrue();
    assertThat(queue.heartbeat(id, "node-other")).isFalse();
  }
  @Test void duplicateIdemKeyReturnsNull() {
    Long a = queue.insertPending(2L, 1L, TriggerType.API, 60, LocalDateTime.now(), LocalDate.now(), "{}", "idem-1");
    Long b = queue.insertPending(2L, 1L, TriggerType.API, 60, LocalDateTime.now(), LocalDate.now(), "{}", "idem-1");
    assertThat(a).isNotNull(); assertThat(b).isNull();
  }
  @Test void retryWaitSetsDbTimeAndPromote() throws Exception {
    Long id = pending(40, "rw1");
    queue.claim("node-y");
    assertThat(queue.retryWait(id, "PUSH-011", "rate limited", 1)).isTrue();
    assertThat(queue.retryWait(id, "PUSH-011", "rate limited", 1)).isFalse(); // 已非 RUNNING
    Thread.sleep(1500);
    assertThat(queue.promoteDueRetries()).isEqualTo(1);
    assertThat(queue.getById(id).getStatus()).isEqualTo(ExecStatus.PENDING.name());
    assertThat(queue.getById(id).getRetryCount()).isEqualTo(1);
  }
  @Test void insertErrorNotSwallowedAsDuplicate() {
    assertThrows(DataIntegrityViolationException.class, () ->
      queue.insertPending(3L, 1L, TriggerType.API, 60, null, LocalDate.now(), "{}", "not-null-test"));
  }
}

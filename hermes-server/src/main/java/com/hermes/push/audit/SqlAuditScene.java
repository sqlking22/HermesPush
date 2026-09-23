package com.hermes.push.audit;

/**
 * SQL 审计场景枚举。
 *
 * <p>M1 已启用：PREVIEW / TRIAL / EXEC / VALIDATION_FAILED。
 * <p>M4b 启用：ALERT_EVAL（告警规则评估）。
 * <p>M5 启用：FANOUT_LIST（广播收件人展开清单）。
 */
public enum SqlAuditScene {
  /** M1: 预览执行（语法校验 + 执行计划） */
  PREVIEW,
  /** M1: 试跑（LIMIT 试跑行数） */
  TRIAL,
  /** M1: 正式执行 */
  EXEC,
  /** M1: 语法/规则校验失败 */
  VALIDATION_FAILED,
  /** M4b: 告警规则评估 */
  ALERT_EVAL,
  /** M5: 广播收件人展开清单 */
  FANOUT_LIST
}

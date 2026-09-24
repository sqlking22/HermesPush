/**
 * 执行相关枚举映射（状态/触发方式），ExecListView 与 ExecDetailDrawer 共用。
 * 映射值与原型 EXEC_STATUS 及后端枚举一致，不得擅自改动。
 */

export const STATUS_MAP = {
  SUCCESS:         { label: '成功',     badge: 'b-ok' },
  PARTIAL_SUCCESS: { label: '部分成功', badge: 'b-part' },
  FAILED:          { label: '失败',     badge: 'b-fail' },
  TIMEOUT:         { label: '超时',     badge: 'b-fail' },
  RUNNING:         { label: '执行中',   badge: 'b-run' },
  PENDING:         { label: '排队中',   badge: 'b-run' },
  RETRY_WAIT:      { label: '等待重试', badge: 'b-warn' },
  CANCELLED:       { label: '已取消',   badge: 'b-gray' }
}

export const TRIGGER_MAP = {
  CRON:   '定时',
  MANUAL: '手动',
  TEST:   '测试',
  TRIAL:  '试运行',
  API:    'API'
}

export const STATUS_OPTIONS = Object.entries(STATUS_MAP).map(([value, m]) => ({
  value, label: m.label
}))

export const TRIGGER_OPTIONS = Object.keys(TRIGGER_MAP)

export function statusBadge(s) {
  return (STATUS_MAP[s] && STATUS_MAP[s].badge) || 'b-gray'
}

export function statusLabel(s) {
  return (STATUS_MAP[s] && STATUS_MAP[s].label) || s || '—'
}

export function triggerLabel(t) {
  return TRIGGER_MAP[t] || t || '—'
}

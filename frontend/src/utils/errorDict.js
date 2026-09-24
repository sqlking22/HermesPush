/**
 * M1 错误码字典，与后端 ErrorCode.java 逐码对应。
 * 键为 code（连字符格式，如 "DS-001"），值为 { userMessage, suggestion }。
 * 字典 miss 时，调用方应 fallback 到后端返回的 errorMsg。
 */
export const ERROR_DICT = {
  'DS-001': { userMessage: '数据库不存在或名称错误', suggestion: '检查 JDBC URL 中的库名' },
  'DS-002': { userMessage: '数据源连接失败或超时', suggestion: '检查网络、账号状态，确认只读账号未过期' },
  'DS-003': { userMessage: '数据库认证失败', suggestion: '检查用户名密码' },
  'SQL-001': { userMessage: 'SQL 语法错误', suggestion: '查看报错定位的关键字' },
  'SQL-002': { userMessage: '仅允许单条 SELECT 查询', suggestion: '移除多余语句或 DDL/DML' },
  'SQL-003': { userMessage: 'SQL 校验失败', suggestion: '见详情' },
  'SQL-004': { userMessage: '必填参数缺失', suggestion: '在触发弹窗补填或为参数设默认值' },
  'SQL-005': { userMessage: '文本替换参数未通过白名单校验', suggestion: '检查参数值是否匹配配置的白名单/正则' },
  'TPL-003': { userMessage: '模板引用了不存在的字段', suggestion: '核对数据集字段名与模板变量' },
  'TPL-010': { userMessage: '模板包含被禁用的危险指令', suggestion: '移除 ?new/?api 等指令' },
  'RD-002': { userMessage: '内容超出渠道长度限制且策略为失败', suggestion: '调大截断阈值或改用文件发送' },
  'PUSH-011': { userMessage: '渠道限流等待超时', suggestion: '错峰调度或调大排队等待超时' },
  'PUSH-012': { userMessage: 'webhook 无效或已失效', suggestion: '在渠道管理执行健康检查' },
  'SYS-001': { userMessage: '加解密失败', suggestion: '检查 HP_MASTER_KEY 是否变更' },
  'SYS-002': { userMessage: '任务状态不允许该操作', suggestion: '刷新后重试' },
  'SYS-003': { userMessage: '资源不存在', suggestion: '检查 ID' },
  'SYS-004': { userMessage: '保存冲突，数据已被他人修改', suggestion: '刷新后重试' },
  'SYS-005': { userMessage: '渲染超时', suggestion: '简化模板或调大超时' },
  'SYS-006': { userMessage: '请求参数校验失败', suggestion: '按提示填写后重试' },
  'AUTH-001': { userMessage: '用户名或密码错误', suggestion: '请重新输入，连续失败将锁定账户(M4a)' },
  'AUTH-002': { userMessage: '未登录或会话已过期', suggestion: '请重新登录' },
  'NODE_LOST': { userMessage: '执行节点失联', suggestion: '稍后自动重跑，或手动触发新执行' }
}

/**
 * 根据 errorCode 获取用户友好的错误信息。
 * @param {string} code - 错误码（如 "SQL-001"）
 * @param {string} fallbackMsg - 字典未命中时的 fallback 文案
 * @returns {{ userMessage: string, suggestion: string, matched: boolean }}
 */
export function resolveError(code, fallbackMsg = '') {
  const hit = ERROR_DICT[code]
  if (hit) {
    return { ...hit, matched: true }
  }
  return {
    userMessage: fallbackMsg || '未知错误',
    suggestion: '请联系管理员排查',
    matched: false
  }
}

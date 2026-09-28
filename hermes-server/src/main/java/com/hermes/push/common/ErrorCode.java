package com.hermes.push.common;

import lombok.Getter;
import java.util.Arrays;

@Getter
public enum ErrorCode {
  DS_001("DS-001","数据库不存在或名称错误","检查 JDBC URL 中的库名"),
  DS_002("DS-002","数据源连接失败或超时","检查网络、账号状态，确认只读账号未过期"),
  DS_003("DS-003","数据库认证失败","检查用户名密码"),
  SQL_001("SQL-001","SQL 语法错误","查看报错定位的关键字"),
  SQL_002("SQL-002","仅允许单条 SELECT 查询","移除多余语句或 DDL/DML"),
  SQL_003("SQL-003","SQL 校验失败","见详情"),
  SQL_004("SQL-004","必填参数缺失","在触发弹窗补填或为参数设默认值"),
  SQL_005("SQL-005","文本替换参数未通过白名单校验","检查参数值是否匹配配置的白名单/正则"),
  TPL_003("TPL-003","模板引用了不存在的字段","核对数据集字段名与模板变量"),
  TPL_010("TPL-010","模板包含被禁用的危险指令","移除 ?new/?api 等指令"),
  RD_002("RD-002","内容超出渠道长度限制且策略为失败","调大截断阈值或改用文件发送"),
  PUSH_011("PUSH-011","渠道限流等待超时","错峰调度或调大排队等待超时"),
  PUSH_012("PUSH-012","webhook 无效或已失效","在渠道管理执行健康检查"),
  SYS_001("SYS-001","加解密失败","检查 HP_MASTER_KEY 是否变更"),
  SYS_002("SYS-002","任务状态不允许该操作","刷新后重试"),
  SYS_003("SYS-003","资源不存在","检查 ID"),
  SYS_004("SYS-004","保存冲突，数据已被他人修改","刷新后重试"),
  SYS_005("SYS-005","渲染超时","简化模板或调大超时"),
  SYS_006("SYS-006","请求参数校验失败","按提示填写后重试"),
  AUTH_001("AUTH-001","用户名或密码错误","请重新输入，连续失败将锁定账户(M4a)"),
  AUTH_002("AUTH-002","未登录或会话已过期","请重新登录"),
  STO_001("STO-001","存储后端不存在或未启用","检查存储配置"),
  STO_002("STO-002","文件读写失败","检查存储后端可用性与权限"),
  STO_003("STO-003","路径非法","检查路径是否含非法字符或穿越"),
  STO_004("STO-004","存储键已存在","换一个存储键");

  private final String code, userMessage, suggestion;
  ErrorCode(String code, String userMessage, String suggestion) {
    this.code = code; this.userMessage = userMessage; this.suggestion = suggestion;
  }
  public static ErrorCode of(String code) {
    return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown error code " + code));
  }
}

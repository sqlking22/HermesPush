package com.hermes.push.dataset;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class DruidSqlValidator {
  private static final Pattern INTO_FILE = Pattern.compile("(?i)INTO\\s+(OUTFILE|DUMPFILE)");
  private static final Pattern FOR_UPDATE_LOCK = Pattern.compile(
      "(?i)\\bFOR\\s+UPDATE\\b|\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b");
  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
  private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

  public String validate(String sql, String dsType) {
    if (sql == null || sql.isBlank()) throw new BizException(ErrorCode.SQL_003, "SQL 为空");
    String stripped = stripComments(sql);
    if (INTO_FILE.matcher(stripped).find())
      throw new BizException(ErrorCode.SQL_002, "禁止 INTO OUTFILE/DUMPFILE");
    if (FOR_UPDATE_LOCK.matcher(stripped).find())
      throw new BizException(ErrorCode.SQL_002, "禁止带锁 SELECT（FOR UPDATE / LOCK IN SHARE MODE）");
    DbType db = switch (dsType) {
      case "MYSQL" -> DbType.mysql;
      case "POSTGRESQL" -> DbType.postgresql;
      case "ORACLE" -> DbType.oracle;
      default -> throw new BizException(ErrorCode.SQL_003, "未知数据源类型 " + dsType);
    };
    List<SQLStatement> stmts;
    try {
      stmts = SQLUtils.parseStatements(sql, db);
    } catch (Exception e) {
      throw new BizException(ErrorCode.SQL_001, firstLine(e.getMessage()));
    }
    if (stmts.size() != 1) throw new BizException(ErrorCode.SQL_002, "只允许单条语句，实际 " + stmts.size() + " 条");
    if (!(stmts.get(0) instanceof SQLSelectStatement ss))
      throw new BizException(ErrorCode.SQL_002, "只允许 SELECT，实际为 " + stmts.get(0).getClass().getSimpleName());
    // AST 级二次校验：INTO 子句 + FOR UPDATE（正则已兜注释绕过，AST 做根治）
    if (ss.getSelect().getQuery() instanceof SQLSelectQueryBlock block) {
      if (block.getInto() != null)
        throw new BizException(ErrorCode.SQL_002, "禁止 INTO 子句");
      if (block.isForUpdate())
        throw new BizException(ErrorCode.SQL_002, "禁止 FOR UPDATE");
    }
    return sql;
  }

  private String stripComments(String sql) {
    String s = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
    s = LINE_COMMENT.matcher(s).replaceAll(" ");
    return s;
  }

  private String firstLine(String s) { return s == null ? "" : s.split("\n")[0]; }
}

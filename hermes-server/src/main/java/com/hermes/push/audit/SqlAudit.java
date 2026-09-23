package com.hermes.push.audit;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("hp_sql_audit")
public class SqlAudit {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String scene;
  private Long execId;
  private String operator;
  private Long datasourceId;
  private String sqlText;
  @TableField("params_json")
  private String paramsJson;
  private Integer rowsReturned;
  private Long costMs;
  private String clientIp;
  @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime createdAt;
}

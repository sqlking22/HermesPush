package com.hermes.push.exec;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("hp_task_exec")
public class TaskExec {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long taskId;
  private Long taskVersionId;
  private String triggerType;
  private Integer priority;
  private String status;
  private LocalDateTime fireTime;
  private LocalDate bizDate;
  private String paramsJson;
  private String idempotencyKey;

  @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private String idemKeyEff;

  private Integer retryCount;
  private Integer maxRetry;
  private LocalDateTime nextRetryAt;
  private String nodeId;
  private LocalDateTime heartbeatAt;
  private Integer rowsTotal;
  private String stageCostsJson;
  private Long costMs;
  private String errorCode;
  private String errorMsg;
  private Long parentExecId;
  private String fanoutKey;

  @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime createdAt;

  @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime updatedAt;
}

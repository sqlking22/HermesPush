package com.hermes.push.task;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("hp_task")
public class Task {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String name;
  @TableField("task_key")
  private String taskKey;
  @TableField("task_type")
  private String taskType;
  private String status;
  @TableField("cron_expr")
  private String cronExpr;
  @TableField("jitter_enabled")
  private Integer jitterEnabled;
  @TableField("current_version_id")
  private Long currentVersionId;
  @TableField("pinned_version_id")
  private Long pinnedVersionId;
  private String owner;
  @TableField("lock_version")
  private Integer lockVersion;
  @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime createdAt;
  @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime updatedAt;
}

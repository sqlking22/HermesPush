package com.hermes.push.task;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("hp_task_version")
public class TaskVersion {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("task_id")
  private Long taskId;
  @TableField("version_no")
  private Integer versionNo;
  @TableField("config_json")
  private String configJson;
  private String remark;
  @TableField("created_by")
  private String createdBy;
  @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime createdAt;
}

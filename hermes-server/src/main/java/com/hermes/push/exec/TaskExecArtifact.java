package com.hermes.push.exec;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("hp_task_exec_artifact")
public class TaskExecArtifact {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long execId;
  private String artifactKey;
  private String type;
  private String renderProvider;
  private Integer rowsCount;
  private Integer bytes;
  private String content;
  private String storageUri;
  private Long costMs;
  private String errorCode;
  private String errorMsg;

  @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime createdAt;
}

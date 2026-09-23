package com.hermes.push.exec;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("hp_task_exec_push")
public class TaskExecPush {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long execId;
  private Long channelId;
  private String artifactKey;
  private String msgType;
  private String status;
  private Integer retryCount;
  private String errorCode;
  private String errorMsg;
  private LocalDateTime sentAt;

  @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime createdAt;
}

package com.hermes.push.channel;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("hp_channel")
public class Channel {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String name;
  private String type;
  private String configCipher;
  private Integer rateLimitPerMin;
  private Integer queueWaitTimeoutSec;
  private Integer testFlag;
  private String status;
  private Integer deleted;
  private String createdBy;

  @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime createdAt;
  @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime updatedAt;
}

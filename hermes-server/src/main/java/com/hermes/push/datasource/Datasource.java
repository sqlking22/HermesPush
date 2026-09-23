package com.hermes.push.datasource;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("hp_datasource")
public class Datasource {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String name;
  private String type;
  private String jdbcUrl;
  private String username;
  private String passwordCipher;
  private Integer roConfirmed;
  private String status;
  private Integer maxRows;
  private Integer queryTimeoutSec;
  private Integer poolMax;
  private String overflowPolicy;
  private String createdBy;
  @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime createdAt;
  @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime updatedAt;
}

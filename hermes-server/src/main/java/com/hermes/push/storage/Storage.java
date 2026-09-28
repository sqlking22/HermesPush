package com.hermes.push.storage;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("hp_storage")
public class Storage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String storageKey;
    private String displayName;
    private String storageType;
    private String endpoint;
    private String region;
    private String bucket;
    private String prefix;
    private String accessKeyEnc;
    private String secretKeyEnc;
    private Integer enabled;
    private String createdBy;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    @TableField(value = "updated_at", updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}

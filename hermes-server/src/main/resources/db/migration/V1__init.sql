CREATE TABLE hp_datasource (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(16) NOT NULL COMMENT 'MYSQL|POSTGRESQL|ORACLE',
  jdbc_url VARCHAR(512) NOT NULL,
  username VARCHAR(128) NOT NULL,
  password_cipher VARCHAR(512) NOT NULL,
  ro_confirmed TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED|DISABLED',
  max_rows INT NOT NULL DEFAULT 50000,
  query_timeout_sec INT NOT NULL DEFAULT 60,
  pool_max INT NOT NULL DEFAULT 5,
  overflow_policy VARCHAR(16) NOT NULL DEFAULT 'TRUNCATE' COMMENT 'TRUNCATE|FAIL',
  created_by VARCHAR(64), created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ds_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_channel (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(32) NOT NULL DEFAULT 'WEWORK_BOT',
  config_cipher VARCHAR(1024) NOT NULL COMMENT 'JSON:{"webhook":...} AES-GCM',
  rate_limit_per_min INT NOT NULL DEFAULT 20,
  queue_wait_timeout_sec INT NOT NULL DEFAULT 300,
  test_flag TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64), created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ch_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_whitelist (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  type VARCHAR(32) NOT NULL COMMENT 'WEBHOOK_HOST|EMAIL_DOMAIN|EMAIL_ADDRESS',
  value VARCHAR(255) NOT NULL,
  created_by VARCHAR(64), created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_wl (type, value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO hp_whitelist(type,value,created_by) VALUES
 ('WEBHOOK_HOST','qyapi.weixin.qq.com','system'),
 ('WEBHOOK_HOST','oapi.dingtalk.com','system'),
 ('WEBHOOK_HOST','open.feishu.cn','system');

CREATE TABLE hp_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  task_key VARCHAR(64) NOT NULL,
  task_type VARCHAR(16) NOT NULL DEFAULT 'REPORT',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT|ONLINE|OFFLINE|PAUSED|DELETED',
  cron_expr VARCHAR(64),
  jitter_enabled TINYINT NOT NULL DEFAULT 0,
  current_version_id BIGINT, pinned_version_id BIGINT,
  owner VARCHAR(64) NOT NULL,
  lock_version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_task_key (task_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_task_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  config_json JSON NOT NULL,
  remark VARCHAR(255),
  created_by VARCHAR(64), created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tv (task_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_task_exec (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  task_version_id BIGINT NOT NULL,
  trigger_type VARCHAR(16) NOT NULL COMMENT 'CRON|MANUAL|TEST|TRIAL|API',
  priority TINYINT NOT NULL DEFAULT 40 COMMENT 'TRIAL/TEST=70 MANUAL/API=60 CRON=40',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING|RUNNING|RETRY_WAIT|SUCCESS|PARTIAL_SUCCESS|FAILED|TIMEOUT|CANCELLED',
  fire_time DATETIME(3) NOT NULL,
  biz_date DATE,
  params_json JSON,
  idempotency_key VARCHAR(128) NULL,
  idem_key_eff VARCHAR(128) AS (IFNULL(idempotency_key,'')) STORED,
  retry_count INT NOT NULL DEFAULT 0,
  max_retry INT NOT NULL DEFAULT 3,
  next_retry_at DATETIME(3) NULL,
  node_id VARCHAR(64), heartbeat_at DATETIME(3),
  rows_total INT, stage_costs_json JSON, cost_ms BIGINT,
  error_code VARCHAR(32), error_msg VARCHAR(1024),
  parent_exec_id BIGINT NULL, fanout_key VARCHAR(128) NULL,
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_fire (task_id, fire_time, trigger_type, idem_key_eff),
  UNIQUE KEY uk_idem (task_id, idempotency_key),
  UNIQUE KEY uk_fanout (parent_exec_id, fanout_key),
  KEY idx_claim (status, priority, fire_time),
  KEY idx_task_time (task_id, fire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_task_exec_push (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  exec_id BIGINT NOT NULL, channel_id BIGINT NOT NULL,
  artifact_key VARCHAR(64) NOT NULL, msg_type VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|SUCCESS|FAILED|SKIPPED',
  retry_count INT NOT NULL DEFAULT 0,
  error_code VARCHAR(32), error_msg VARCHAR(512),
  sent_at DATETIME(3), created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_push (exec_id, channel_id, artifact_key, msg_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_task_exec_artifact (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  exec_id BIGINT NOT NULL, artifact_key VARCHAR(64) NOT NULL,
  type VARCHAR(16) NOT NULL DEFAULT 'MARKDOWN',
  render_provider VARCHAR(32), rows_count INT, bytes INT,
  content MEDIUMTEXT NULL COMMENT 'M1 文本产物内联；M2 起改 storage_uri',
  storage_uri VARCHAR(512) NULL, cost_ms BIGINT,
  error_code VARCHAR(32), error_msg VARCHAR(512),
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_art (exec_id, artifact_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_sql_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  scene VARCHAR(24) NOT NULL COMMENT 'PREVIEW|TRIAL|EXEC|VALIDATION_FAILED',
  exec_id BIGINT NULL, operator VARCHAR(64), datasource_id BIGINT NOT NULL,
  sql_text TEXT NOT NULL, params_json JSON,
  rows_returned INT, cost_ms BIGINT, client_ip VARCHAR(64),
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_scene_time (scene, created_at), KEY idx_ds (datasource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hp_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'ADMIN',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

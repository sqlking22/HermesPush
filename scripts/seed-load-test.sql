-- ============================================================
--  HermesPush M1 峰值压测造数脚本
--  用法：连接 hermes 库执行（本脚本默认 USE hermes）
USE hermes;
--  作用：
--    1. 基于已有任务，批量克隆 50 个压测任务（task_key 带 loadtest_ 前缀）
--    2. 生成 50 条 PENDING 执行（fire_time=NOW()），跳过 Quartz 直接灌队列
--    3. 压测完成后执行末尾 CLEANUP 段清理
-- ============================================================

-- ---------- 配置 ----------
-- 作为克隆模板的任务 ID（E2E 步骤 4 创建的日报任务）
SET @template_task_id = (SELECT id FROM hp_task WHERE task_key = 'daily-sales-report' LIMIT 1);
SET @load_tag = 'loadtest';

-- ---------- 1. 克隆 50 个任务 ----------
-- 若模板不存在，手动报错
SELECT @template_task_id AS template_task_id;

DROP PROCEDURE IF EXISTS sp_load_seed;
DELIMITER //
CREATE PROCEDURE sp_load_seed()
BEGIN
  DECLARE i INT DEFAULT 1;
  DECLARE v_template_ver_id BIGINT;
  DECLARE v_config JSON;

  SELECT current_version_id INTO v_template_ver_id
  FROM hp_task WHERE id = @template_task_id;

  SELECT config_json INTO v_config
  FROM hp_task_version WHERE id = v_template_ver_id;

  WHILE i <= 50 DO
    SET @task_key = CONCAT(@load_tag, '_task_', LPAD(i, 3, '0'));
    SET @task_name = CONCAT('[压测] 日报任务 #', LPAD(i, 3, '0'));

    -- 插入任务（DRAFT 状态，避免 Quartz 调度，publish 由脚本后补 ONLINE 但不注册 Quartz）
    INSERT INTO hp_task(name, task_key, task_type, status, cron_expr, owner)
    VALUES (@task_name, @task_key, 'REPORT', 'DRAFT', '0 0 9 * * ?', 'admin');

    SET @new_task_id = LAST_INSERT_ID();

    -- 插入版本（version_no=1）
    INSERT INTO hp_task_version(task_id, version_no, config_json, remark, created_by)
    VALUES (@new_task_id, 1, v_config, 'load test clone', 'admin');

    SET @new_ver_id = LAST_INSERT_ID();

    -- 更新任务 current_version_id
    UPDATE hp_task SET current_version_id = @new_ver_id WHERE id = @new_task_id;

    SET i = i + 1;
  END WHILE;
END //
DELIMITER ;

CALL sp_load_seed();
DROP PROCEDURE sp_load_seed;

-- ---------- 2. 生成 50 条 PENDING 执行（fire_time=NOW()）----------
INSERT INTO hp_task_exec
  (task_id, task_version_id, trigger_type, priority, status, fire_time, biz_date, params_json, idempotency_key)
SELECT
  t.id,
  t.current_version_id,
  'MANUAL' AS trigger_type,
  60 AS priority,
  'PENDING' AS status,
  NOW(3) AS fire_time,
  DATE_SUB(CURDATE(), INTERVAL 1 DAY) AS biz_date,
  '{}' AS params_json,
  CONCAT(@load_tag, '-', t.task_key, '-', UNIX_TIMESTAMP(NOW(3))) AS idempotency_key
FROM hp_task t
WHERE t.task_key LIKE CONCAT(@load_tag, '\_task\_%') ESCAPE '\\';

-- ---------- 3. 观察指标 SQL（压测过程中手动执行）----------
-- 全部完成耗时（秒）
-- SELECT
--   MAX(TIMESTAMPDIFF(SECOND, fire_time, updated_at)) AS p100_s
-- FROM hp_task_exec
-- WHERE task_id IN (SELECT id FROM hp_task WHERE task_key LIKE CONCAT(@load_tag, '\_task\_%') ESCAPE '\\');

-- P95（按耗时排序取第 48 条，50×5%≈2.5，跳过 2 条取第 3 条）
-- SELECT TIMESTAMPDIFF(SECOND, fire_time, updated_at) AS cost_s
-- FROM hp_task_exec
-- WHERE task_id IN (SELECT id FROM hp_task WHERE task_key LIKE CONCAT(@load_tag, '\_task\_%') ESCAPE '\\')
-- ORDER BY cost_s DESC
-- LIMIT 2, 1;

-- 各状态统计
-- SELECT status, COUNT(*) FROM hp_task_exec
-- WHERE task_id IN (SELECT id FROM hp_task WHERE task_key LIKE CONCAT(@load_tag, '\_task\_%') ESCAPE '\\')
-- GROUP BY status;

-- ---------- CLEANUP（压测完成后执行）----------
-- DELETE FROM hp_task_exec WHERE task_id IN (SELECT id FROM hp_task WHERE task_key LIKE 'loadtest\_task\_%' ESCAPE '\\');
-- DELETE FROM hp_task_exec_artifact WHERE exec_id NOT IN (SELECT id FROM hp_task_exec);
-- DELETE FROM hp_task_exec_push WHERE exec_id NOT IN (SELECT id FROM hp_task_exec);
-- DELETE FROM hp_sql_audit WHERE exec_id IN (SELECT id FROM hp_task_exec WHERE task_id IN (SELECT id FROM hp_task WHERE task_key LIKE 'loadtest\_task\_%' ESCAPE '\\'));
-- DELETE FROM hp_task_version WHERE task_id IN (SELECT id FROM hp_task WHERE task_key LIKE 'loadtest\_task\_%' ESCAPE '\\');
-- DELETE FROM hp_task WHERE task_key LIKE 'loadtest\_task\_%' ESCAPE '\\';

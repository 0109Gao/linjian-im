-- ============================================================
-- W9 收尾迁移：管理员操作日志表
-- 执行：docker exec -i im-mysql mysql -uim -pim123456 im < sql/migrate-w10.sql
--
-- 为什么单独一个脚本：schema.sql 里全是 CREATE TABLE IF NOT EXISTS，
-- 对「新增一张表」这件事它本来就是幂等的——但它的作用范围是「整个库」，
-- 在已经有数据的演示库上执行它，需要确认它不会碰其它表。
-- 这个脚本只做一件事，跑完还能顺手查一遍结果。
--
-- 幂等：重复执行不报错、不丢数据（表已存在时 CREATE 是 no-op）。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `im_admin_log`
(
    `id`          BIGINT       NOT NULL COMMENT '雪花 ID',
    `admin_id`    BIGINT       NOT NULL COMMENT '操作人 user_id',
    `admin_name`  VARCHAR(20)  NOT NULL COMMENT '操作人登录名（冗余，改名不影响历史记录）',
    `action`      VARCHAR(32)  NOT NULL COMMENT 'USER_DISABLE / USER_ENABLE / MSG_AUDIT_VIEW / SEQ_REBUILD',
    `target_type` VARCHAR(16)  NOT NULL COMMENT 'USER / SESSION / SYSTEM',
    `target_id`   BIGINT       NULL COMMENT '目标 ID；SYSTEM 类操作没有目标',
    `detail`      VARCHAR(512) NULL COMMENT '人可读的补充说明',
    `ip`          VARCHAR(45)  NULL COMMENT '来源 IP',
    `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_created_at` (`created_at`),
    KEY `idx_admin_time` (`admin_id`, `created_at`),
    KEY `idx_target_time` (`target_type`, `target_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='管理员操作日志';

-- 复核：表在、索引在、当前行数（新装一定是 0）
SELECT COUNT(*) AS existing_rows FROM `im_admin_log`;

SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_index
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'im_admin_log'
 GROUP BY INDEX_NAME;

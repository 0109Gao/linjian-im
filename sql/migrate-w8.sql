-- ============================================================
-- W8 增量迁移：im_session 增加 name 列（群名）
--
-- 为什么单独一个文件而不是直接改 schema.sql：
-- schema.sql 全部是 CREATE TABLE IF NOT EXISTS，对「表已存在」的库完全不起作用，
-- 已有的表不会凭空多出一列。新建库走 schema.sql，升级库走本文件。
--
-- 为什么不写裸 ALTER：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报
-- 1060 Duplicate column name。这里查 information_schema 再决定执行哪条语句，
-- 保证本文件可以反复执行（幂等），和 schema.sql 的幂等风格一致。
--
-- 执行方式见「本地运行说明.md」；开发期最省事的做法仍是 down -v 重建卷。
-- ============================================================

SET NAMES utf8mb4;

SET @has_name := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'im_session'
       AND COLUMN_NAME = 'name'
);

SET @ddl := IF(@has_name = 0,
    'ALTER TABLE `im_session` ADD COLUMN `name` VARCHAR(64) DEFAULT NULL COMMENT ''群名；单聊为 NULL（单聊没有名字，展示靠对方昵称）'' AFTER `session_type`',
    'SELECT ''im_session.name 已存在，跳过'' AS skipped');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 已有的单聊会话不需要回填：name 对单聊的定义就是 NULL。
-- 已有的群聊会话（W8 之前不存在）因此也不需要 UPDATE。

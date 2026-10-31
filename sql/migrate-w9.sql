-- ============================================================
-- W9 迁移：账号增加角色
-- 执行：docker exec -i im-mysql mysql -uim -pim123456 im < sql/migrate-w9.sql
--
-- 为什么需要这个脚本：schema.sql 里全部是 CREATE TABLE IF NOT EXISTS，
-- 它对「已存在的表」只做一件事——什么都不做。因此给老库加列时，
-- 跑一遍 schema.sql 看起来「执行成功」，列却根本不存在，
-- 直到建群或登录时报 Unknown column 'role'。W8 的 name 列踩过同一个坑。
--
-- 幂等做法：先查 information_schema 判断列是否已存在，再决定执行 ALTER 还是跳过。
-- 这样脚本可以重复执行，也可以对全新的库（schema.sql 已建好列）安全跑一遍。
-- ============================================================

SET NAMES utf8mb4;

SET @col_exists := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'im_user'
       AND COLUMN_NAME = 'role'
);

SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `im_user` ADD COLUMN `role` TINYINT NOT NULL DEFAULT 0 COMMENT ''0 普通用户 / 1 管理员（W9）'' AFTER `status`',
    'SELECT ''im_user.role 已存在，跳过'' AS skipped');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 演示环境的管理员账号。password 为 123456 的 BCrypt 哈希（与 seed-dev.sql 中一致）。
-- 用 INSERT ... ON DUPLICATE KEY UPDATE 而不是纯 UPDATE：老库里根本没有 admin
-- 这个账号（seed-dev.sql 的旧版本只有 linjian 与 demo），纯 UPDATE 会静默影响 0 行，
-- 表现为「迁移跑成功，但管理端没人能登录」。
INSERT INTO `im_user` (`id`, `username`, `password_hash`, `nickname`, `status`, `role`, `created_at`)
VALUES (109859320094527490, 'admin',
        '$2a$10$mCxi0cgBz.dwNxMh6t5H5Oq8rpDSbBP5TJOhxj022kqSQBHOpOHAm',
        '林间管理员', 1, 1, NOW())
ON DUPLICATE KEY UPDATE `role` = 1, `status` = 1;

SELECT `id`, `username`, `nickname`, `status`, `role`
  FROM `im_user`
 ORDER BY `role` DESC, `id`;

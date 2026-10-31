-- ============================================================
-- 测试库。文件名以 00 开头，保证在 01-schema.sql 之前执行。
--
-- 为什么不把集成测试跑在 im 库上：集成测试需要清表、需要断言「未读数为 0」
-- 这类绝对值，一旦和演示数据混在同一个库，断言就得处处加 where 条件，
-- 而且跑一次测试就会把演示账号的会话数据洗掉。独立一个库最省事。
--
-- 表结构不在这里建：由测试启动时读取 classpath 下的 schema.sql 建，
-- 保证测试库与生产库的结构永远来自同一份 DDL。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `im_test`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

-- 应用账号 im 默认只被授权到 MYSQL_DATABASE（即 im），这里显式补上 im_test 的权限，
-- 否则测试连库会报 Access denied。
GRANT ALL PRIVILEGES ON `im_test`.* TO 'im'@'%';
FLUSH PRIVILEGES;

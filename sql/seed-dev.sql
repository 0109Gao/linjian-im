-- ============================================================
-- 演示账号种子数据
-- 两个账号密码均为 123456，哈希由 BCrypt(cost=10) 实际生成并校验通过，非手工拼写。
-- 使用 INSERT IGNORE：容器重建时 init 脚本会再执行一次，重复插入应静默跳过而不是中断启动。
-- ============================================================

-- 同 schema.sql：显式声明连接字符集，否则中文昵称会以 latin1 解释后双编码入库。
SET NAMES utf8mb4;

INSERT IGNORE INTO `im_user` (`id`, `username`, `password_hash`, `nickname`, `status`, `created_at`)
VALUES (109859320094527488, 'linjian',
        '$2a$10$mCxi0cgBz.dwNxMh6t5H5Oq8rpDSbBP5TJOhxj022kqSQBHOpOHAm',
        '林间', 1, NOW()),
       (109859320094527489, 'demo',
        '$2a$10$nfYBP/E9OJw66AVEGBCLGu6r0lwkl40pa2E/Y92U7xsZgGaq4ernS',
        '演示用户', 1, NOW());

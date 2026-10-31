-- ============================================================
-- 演示账号种子数据
-- 两个账号密码均为 123456，哈希由 BCrypt(cost=10) 实际生成并校验通过，非手工拼写。
-- 使用 INSERT IGNORE：容器重建时 init 脚本会再执行一次，重复插入应静默跳过而不是中断启动。
-- ============================================================

-- 同 schema.sql：显式声明连接字符集，否则中文昵称会以 latin1 解释后双编码入库。
SET NAMES utf8mb4;

INSERT IGNORE INTO `im_user` (`id`, `username`, `password_hash`, `nickname`, `status`, `role`, `created_at`)
VALUES (109859320094527488, 'linjian',
        '$2a$10$mCxi0cgBz.dwNxMh6t5H5Oq8rpDSbBP5TJOhxj022kqSQBHOpOHAm',
        '林间', 1, 0, NOW()),
       (109859320094527489, 'demo',
        '$2a$10$nfYBP/E9OJw66AVEGBCLGu6r0lwkl40pa2E/Y92U7xsZgGaq4ernS',
        '演示用户', 1, 0, NOW()),
       -- W9 新增：管理端需要一个能进后台的账号。密码同样是 123456。
       -- 单独一个账号而不是把 linjian 提成管理员，是为了让「普通用户」与
       -- 「管理员」在演示时能同时存在——管理端禁用某个账号时，需要一个
       -- 不会被自己禁掉的视角。
       (109859320094527490, 'admin',
        '$2a$10$mCxi0cgBz.dwNxMh6t5H5Oq8rpDSbBP5TJOhxj022kqSQBHOpOHAm',
        '林间管理员', 1, 1, NOW());

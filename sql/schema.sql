-- ============================================================
-- 林间即时通讯系统 · 库表结构
-- 数据库：im    字符集：utf8mb4
-- W6 阶段仅包含账号表，会话/消息/群组/已读位点/收件箱在 W7-W8 追加。
-- ============================================================

-- 必须显式声明：容器内 mysql 客户端的 character_set_client 默认为 latin1，
-- 若不声明，脚本里的中文会被按 latin1 解释后存成双编码（实测踩到过，
-- 中文昵称在库里变成 C3A6C5BE... 这样的字节）。SET NAMES 覆盖客户端默认值。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `im_user`
(
    `id`            BIGINT       NOT NULL COMMENT '雪花 ID，由应用侧生成，非自增',
    `username`      VARCHAR(20)  NOT NULL COMMENT '登录名，4-20 位字母数字下划线',
    `password_hash` VARCHAR(72)  NOT NULL COMMENT 'BCrypt 哈希，含盐，长度固定 60',
    `nickname`      VARCHAR(20)  NOT NULL COMMENT '展示名',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 / 0 禁用',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='账号表';

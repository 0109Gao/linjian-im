-- ============================================================
-- 林间即时通讯系统 · 库表结构
-- 数据库：im    字符集：utf8mb4
-- W6：账号表。W7：会话 / 会话成员 / 消息 / 离线收件箱 / 已读游标。
-- W8 将追加：群组表（im_group / im_group_member）。
--
-- 全部 DDL 使用 CREATE TABLE IF NOT EXISTS，可在应用启动时重复执行，
-- 集成测试因此可以直接复用同一份脚本初始化 im_test 库，不需要另维护一份。
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

-- ------------------------------------------------------------
-- 会话。单聊与群聊共用一张表，靠 session_type 区分。
-- biz_key 只在单聊时非空，值为「较小 userId : 较大 userId」，靠唯一索引
-- 把「两个人同时点开对方」这种并发重复创建交给数据库裁决，而不是应用层加锁。
-- 群聊时 biz_key 为 NULL——MySQL 的唯一索引允许多个 NULL，因此群聊会话不会互相冲突。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_session`
(
    `id`               BIGINT      NOT NULL COMMENT '雪花 ID',
    `session_type`     TINYINT     NOT NULL DEFAULT 1 COMMENT '1 单聊 / 2 群聊',
    `name`             VARCHAR(64) DEFAULT NULL COMMENT '群名；单聊为 NULL（单聊没有名字，展示靠对方昵称）',
    `biz_key`          VARCHAR(64) DEFAULT NULL COMMENT '单聊：min(uid):max(uid)；群聊：NULL',
    `last_msg_seq`     BIGINT      NOT NULL DEFAULT 0 COMMENT '会话内最后一条消息的 seq，0 表示尚无消息',
    `last_msg_at`      DATETIME(3) DEFAULT NULL COMMENT '最后一条消息时间，会话列表按它倒序',
    `last_msg_preview` VARCHAR(64) DEFAULT NULL COMMENT '最后一条消息摘要，仅供列表展示',
    `created_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_key` (`biz_key`),
    KEY `idx_last_msg_at` (`last_msg_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='会话';

-- ------------------------------------------------------------
-- 会话成员。为什么不把 user_a / user_b 塞进 im_session：
-- 「我参与的会话有哪些」是会话列表的唯一入口查询，而 biz_key 是拼接串，
-- 用它无法走索引回答「包含我的会话」。统一成成员表后，会话列表与
-- 「你是不是这个会话的人」这条鉴权查询共用同一个索引。
-- 群聊在 W8 直接复用本表，不需要改结构。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_session_member`
(
    `session_id` BIGINT   NOT NULL,
    `user_id`    BIGINT   NOT NULL,
    `joined_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`session_id`, `user_id`),
    KEY `idx_user` (`user_id`, `session_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='会话成员';

-- ------------------------------------------------------------
-- 消息本体，全局一份（读扩散）。seq 是会话内单调递增序号，由 Redis INCR 分配，
-- 允许出现空洞：INCR 成功但 INSERT 失败时该号被消耗。拉取一律用 seq > N 的范围查询，
-- 不要求连续，因此空洞不影响正确性；未读数用 COUNT(im_inbox) 而非 maxSeq - lastReadSeq，
-- 同样不受空洞影响。
-- uk_sender_client 是幂等的最后一道闸：客户端重发携带同一个 client_msg_id 时，
-- 插入会撞唯一键，服务端据此回查原消息并返回原 seq。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_message`
(
    `id`            BIGINT        NOT NULL COMMENT '雪花 ID',
    `session_id`    BIGINT        NOT NULL,
    `seq`           BIGINT        NOT NULL COMMENT '会话内序号，Redis INCR 分配',
    `sender_id`     BIGINT        NOT NULL,
    `content_type`  TINYINT       NOT NULL DEFAULT 1 COMMENT '1 文本（W7 只有文本）',
    `content`       VARCHAR(2000) NOT NULL COMMENT '正文，服务端按字符数不超过 2000 校验',
    `client_msg_id` VARCHAR(64)   NOT NULL COMMENT '客户端生成的幂等键，重发时保持不变',
    `created_at`    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_seq` (`session_id`, `seq`),
    UNIQUE KEY `uk_sender_client` (`sender_id`, `client_msg_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='消息';

-- ------------------------------------------------------------
-- 未读收件箱。语义是「该用户当前未读的消息引用集合」，不是投递队列。
-- 注意：在线用户同样写本表。W7 实现时偏离了最初「只给离线用户写」的设计，
-- 因为「推到连接上」不等于「人看过了」——在线但没打开会话时若不写，未读数永远是 0，
-- 红点不会亮。代价是每条消息多一次 INSERT，换来的是未读数始终准确。
-- 发送者自己不写（自己的消息不该给自己亮红点）。
-- 用户上报已读时 DELETE seq <= lastReadSeq 的行，因此
--   未读数 = COUNT(*) WHERE user_id = ? AND session_id = ?
-- 一条走唯一索引前缀的查询就能算准，且不受 seq 空洞影响。
-- 定时任务只做兜底：清理超过 30 天仍未读的残留。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_inbox`
(
    `id`         BIGINT   NOT NULL COMMENT '雪花 ID',
    `user_id`    BIGINT   NOT NULL,
    `session_id` BIGINT   NOT NULL,
    `seq`        BIGINT   NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_session_seq` (`user_id`, `session_id`, `seq`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='离线未读收件箱';

-- ------------------------------------------------------------
-- 已读游标。维度是「用户」，没有 device_type——这是 W8 重新确认过的取舍，不是遗漏。
-- 加设备维度会直接产生「手机已读、电脑仍亮红点」这种反直觉状态；
-- 且 W6 的 CONNECT 帧里没有可信的设备标识来源，只能让客户端自报，
-- 等于造一个可以随便填的维度。多端同步改用「已读事件广播给本人其他连接」实现：
-- 一端上报已读，服务端把结果推到 /user/queue/read，其他端据此清红点。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_read_cursor`
(
    `session_id`    BIGINT      NOT NULL,
    `user_id`       BIGINT      NOT NULL,
    `last_read_seq` BIGINT      NOT NULL DEFAULT 0,
    `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`session_id`, `user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='已读游标';

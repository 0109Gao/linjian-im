-- ============================================================
-- 压测造数脚本（W8）
--
-- 目的：给「不引入 Redis 缓存」这个决定提供可验证的依据。
-- 假设是「单机 MySQL 在这个数据量下够快」——那就要真的造出这个数据量，
-- 再用 EXPLAIN ANALYZE 看执行计划和实际耗时，而不是凭感觉说够用。
--
-- 数据形状（默认参数，约 30 万条消息）：
--   1 个热会话 990001：20 万条消息，用来验证「深翻历史」与「seq 范围补拉」
--   500 个冷会话 990100..990599：每个 200 条，用来验证「会话列表分页」
--   用户 900001 参与全部 501 个会话，900002 只在热会话里
--
-- 幂等：先按 id 区间删掉上次造的数据，再重建。可以反复执行。
--
-- 用法：
--   docker exec -i im-mysql mysql -uim -pim123456 im_perf < sql/seed-bulk.sql
-- 或在任意库上执行 CALL seed_bulk(200000, 500, 200);
-- ============================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS seed_bulk;

DELIMITER $$

CREATE PROCEDURE seed_bulk(IN p_hot INT, IN p_cold_sessions INT, IN p_per_cold INT)
BEGIN
    DECLARE v_batch INT DEFAULT 20000;
    DECLARE v_i INT DEFAULT 0;

    -- 递归 CTE 默认深度只有 1000，不放开这个限制第一批就会报 3636
    SET SESSION cte_max_recursion_depth = 100000;

    -- ---------- 清理 ----------
    DELETE FROM im_message WHERE session_id BETWEEN 990000 AND 991000;
    DELETE FROM im_session_member WHERE session_id BETWEEN 990000 AND 991000;
    DELETE FROM im_session WHERE id BETWEEN 990000 AND 991000;

    -- ---------- 用户 ----------
    -- password_hash 用占位串：压测只走 JWT，不经过密码校验
    INSERT IGNORE INTO im_user (id, username, password_hash, nickname, status)
    VALUES (900001, 'perf_a', 'perf-placeholder-not-a-bcrypt-hash', '压测甲', 1),
           (900002, 'perf_b', 'perf-placeholder-not-a-bcrypt-hash', '压测乙', 1);

    -- ---------- 会话 ----------
    INSERT IGNORE INTO im_session (id, session_type, name, biz_key, last_msg_seq, last_msg_at, last_msg_preview)
    VALUES (990001, 2, '热会话（大历史）', NULL, 0, NULL, NULL);

    INSERT IGNORE INTO im_session_member (session_id, user_id, joined_at)
    VALUES (990001, 900001, NOW()), (990001, 900002, NOW());

    INSERT IGNORE INTO im_session (id, session_type, name, biz_key, last_msg_seq, last_msg_at, last_msg_preview)
    WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < p_cold_sessions - 1)
    SELECT 990100 + i,
           2,
           CONCAT('压测群 ', i),
           NULL,
           p_per_cold,
           TIMESTAMPADD(SECOND, p_per_cold, '2026-01-01 00:00:00'),
           CONCAT('压测消息 seq=', p_per_cold)
      FROM n;

    INSERT IGNORE INTO im_session_member (session_id, user_id, joined_at)
    WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < p_cold_sessions - 1)
    SELECT 990100 + i, 900001, NOW() FROM n;

    -- ---------- 热会话消息：分批插，单条 SQL 插 20 万行会撑爆 redo ----------
    SET v_i = 0;
    WHILE v_i < p_hot DO
        INSERT INTO im_message (id, session_id, seq, sender_id, content_type, content, client_msg_id, created_at)
        WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < LEAST(v_batch, p_hot - v_i))
        SELECT 990001000000 + v_i + i,
               990001,
               v_i + i,
               IF((v_i + i) % 2 = 0, 900001, 900002),
               1,
               CONCAT('压测消息 seq=', v_i + i),
               CONCAT('seed-hot-', v_i + i),
               TIMESTAMPADD(SECOND, v_i + i, '2026-01-01 00:00:00')
          FROM n;
        SET v_i = v_i + v_batch;
    END WHILE;

    -- ---------- 冷会话消息：每个会话 p_per_cold 条 ----------
    SET v_i = 0;
    WHILE v_i < p_cold_sessions DO
        INSERT INTO im_message (id, session_id, seq, sender_id, content_type, content, client_msg_id, created_at)
        WITH RECURSIVE n(j) AS (SELECT 0 UNION ALL SELECT j + 1 FROM n WHERE j < p_per_cold - 1)
        SELECT (990100 + v_i) * 1000000 + j + 1,
               990100 + v_i,
               j + 1,
               IF(j % 2 = 0, 900001, 900002),
               1,
               CONCAT('压测消息 seq=', j + 1),
               CONCAT('seed-cold-', 990100 + v_i, '-', j + 1),
               TIMESTAMPADD(SECOND, j + 1, '2026-01-01 00:00:00')
          FROM n;
        SET v_i = v_i + 1;
    END WHILE;

    -- ---------- 收敛热会话的冗余字段 ----------
    UPDATE im_session
       SET last_msg_seq     = p_hot,
           last_msg_at      = TIMESTAMPADD(SECOND, p_hot, '2026-01-01 00:00:00'),
           last_msg_preview = CONCAT('压测消息 seq=', p_hot)
     WHERE id = 990001;
END$$

DELIMITER ;

CALL seed_bulk(200000, 500, 200);
DROP PROCEDURE seed_bulk;

-- 造完顺手确认一下规模，避免后面拿空表跑 EXPLAIN 还以为很快
SELECT (SELECT COUNT(*) FROM im_message) AS messages,
       (SELECT COUNT(*) FROM im_session) AS sessions,
       (SELECT COUNT(*) FROM im_session_member) AS members;

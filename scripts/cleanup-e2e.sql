-- ============================================================
-- 冒烟测试数据清理（e2e/）
--
-- 顺序不能颠倒：
--   1. 先记下受影响的会话 —— 删完消息就查不出「哪些会话被动过」
--   2. 删消息
--   3. 再清孤儿收件箱 —— 必须先删消息，否则此时还查不到孤儿
--   4. 按剩下的消息重算会话摘要
--   5. 把已读游标压回实际最大 seq
--
-- 第 2、3 步的顺序是最初写反过的地方：把「清孤儿」放在「删消息」之前，
-- 执行时一行都匹配不到（此刻还没有孤儿），等消息删完反倒留下了孤儿行。
-- 脚本跑成功不代表做对了，要跟着复核查询一起看。
--
-- 用法：
--   docker exec -i im-mysql mysql -uim -pim123456 im < scripts/cleanup-e2e.sql
-- ============================================================

CREATE TEMPORARY TABLE tmp_e2e AS
SELECT DISTINCT session_id FROM im_message WHERE content LIKE 'E2E%';

DELETE FROM im_message WHERE content LIKE 'E2E%';

DELETE i FROM im_inbox i
WHERE NOT EXISTS (SELECT 1 FROM im_message m WHERE m.session_id = i.session_id AND m.seq = i.seq);

UPDATE im_session s JOIN tmp_e2e t ON t.session_id = s.id
SET s.last_msg_seq     = (SELECT COALESCE(MAX(seq), 0) FROM im_message m WHERE m.session_id = s.id),
    s.last_msg_at      = (SELECT MAX(created_at) FROM im_message m WHERE m.session_id = s.id),
    s.last_msg_preview = (SELECT content FROM im_message m WHERE m.session_id = s.id
                          ORDER BY seq DESC LIMIT 1);

UPDATE im_read_cursor c JOIN tmp_e2e t ON t.session_id = c.session_id
SET c.last_read_seq = (SELECT COALESCE(MAX(seq), 0) FROM im_message m WHERE m.session_id = c.session_id);

DROP TEMPORARY TABLE tmp_e2e;

-- 跑完自检：三个数都应为 0
SELECT '残留 E2E 消息' AS chk, COUNT(*) AS n FROM im_message WHERE content LIKE 'E2E%'
UNION ALL
SELECT '孤儿未读行', COUNT(*)
FROM im_inbox i
WHERE NOT EXISTS (SELECT 1 FROM im_message m WHERE m.session_id = i.session_id AND m.seq = i.seq)
UNION ALL
SELECT '越界游标', COUNT(*)
FROM im_read_cursor c
WHERE c.last_read_seq > COALESCE((SELECT MAX(seq) FROM im_message m WHERE m.session_id = c.session_id), 0);

CREATE TEMPORARY TABLE tmp_e2e AS
SELECT DISTINCT session_id FROM im_message WHERE content LIKE 'E2E%';

DELETE i FROM im_inbox i
WHERE NOT EXISTS (SELECT 1 FROM im_message m WHERE m.session_id = i.session_id AND m.seq = i.seq);

DELETE FROM im_message WHERE content LIKE 'E2E%';

UPDATE im_session s JOIN tmp_e2e t ON t.session_id = s.id
SET s.last_msg_seq  = (SELECT COALESCE(MAX(seq), 0) FROM im_message m WHERE m.session_id = s.id),
    s.last_msg_at   = (SELECT MAX(created_at) FROM im_message m WHERE m.session_id = s.id),
    s.last_msg_preview = (SELECT content FROM im_message m WHERE m.session_id = s.id
                          ORDER BY seq DESC LIMIT 1);

UPDATE im_read_cursor c JOIN tmp_e2e t ON t.session_id = c.session_id
SET c.last_read_seq = (SELECT COALESCE(MAX(seq), 0) FROM im_message m WHERE m.session_id = c.session_id);

DROP TEMPORARY TABLE tmp_e2e;

SELECT COUNT(*) AS e2e_left FROM im_message WHERE content LIKE 'E2E%';
SELECT COUNT(*) AS total_msgs FROM im_message;

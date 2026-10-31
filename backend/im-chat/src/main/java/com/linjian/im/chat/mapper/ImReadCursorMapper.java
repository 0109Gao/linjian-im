package com.linjian.im.chat.mapper;

import com.linjian.im.chat.entity.ImReadCursor;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 已读游标。联合主键，因此不继承 {@code BaseMapper}。
 *
 * <p>写入用 {@code ON DUPLICATE KEY UPDATE} 一条语句完成 upsert。关键是
 * {@code GREATEST}：客户端上报可能乱序到达（先报 10、再报 8），
 * 若直接覆盖，游标会倒退，用户会看到已经读过的消息重新变红。</p>
 */
@Mapper
public interface ImReadCursorMapper {

    @Insert("""
            INSERT INTO im_read_cursor (session_id, user_id, last_read_seq, updated_at)
            VALUES (#{sessionId}, #{userId}, #{seq}, NOW(3))
            ON DUPLICATE KEY UPDATE
                last_read_seq = GREATEST(last_read_seq, VALUES(last_read_seq)),
                updated_at = NOW(3)
            """)
    int upsert(@Param("sessionId") Long sessionId,
               @Param("userId") Long userId,
               @Param("seq") long seq);

    @Select("SELECT * FROM im_read_cursor WHERE session_id = #{sessionId} AND user_id = #{userId}")
    ImReadCursor selectOne(@Param("sessionId") Long sessionId, @Param("userId") Long userId);
}

package com.linjian.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linjian.im.chat.entity.ImSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ImSessionMapper extends BaseMapper<ImSession> {

    /**
     * 插入或静默忽略。
     *
     * <p>为什么不用「先 select 再 insert、捕获 DuplicateKeyException 回查」的写法：
     * 那是两个来回，而且在事务里捕获唯一键冲突后，事务是否还可继续取决于数据库。
     * {@code INSERT IGNORE} 一条语句完成「不存在则建、已存在则不动」，
     * 返回 0 就说明别人抢先建好了，调用方直接按 bizKey 回查即可。</p>
     *
     * <p>代价是 IGNORE 会连带吞掉其他错误（例如字段超长被截断）。这里的字段
     * 全部由服务端自己拼装，长度可控，因此这个代价可以接受。</p>
     */
    @Insert("""
            INSERT IGNORE INTO im_session
                (id, session_type, name, biz_key, last_msg_seq, last_msg_at, last_msg_preview, created_at)
            VALUES
                (#{id}, #{sessionType}, #{name}, #{bizKey}, 0, NULL, NULL, NOW())
            """)
    int insertIgnore(ImSession session);

    @Select("SELECT * FROM im_session WHERE biz_key = #{bizKey}")
    ImSession selectByBizKey(@Param("bizKey") String bizKey);

    /**
     * 更新会话的「最后一条消息」冗余字段。
     *
     * <p>带 {@code last_msg_seq < #{seq}} 条件是为了防止乱序：两条消息并发落库时，
     * 序号小的那条可能后执行 UPDATE，若不设条件会把会话列表的摘要改回旧消息。</p>
     */
    @Update("""
            UPDATE im_session
               SET last_msg_seq = #{seq},
                   last_msg_at = #{at},
                   last_msg_preview = #{preview}
             WHERE id = #{sessionId}
               AND last_msg_seq < #{seq}
            """)
    int touchLastMessage(@Param("sessionId") Long sessionId,
                         @Param("seq") Long seq,
                         @Param("at") LocalDateTime at,
                         @Param("preview") String preview);

    /**
     * 我参与的会话，按最近消息时间倒序。
     *
     * <p>没有消息的会话 {@code last_msg_at} 为 NULL，在 DESC 排序下落到末尾，
     * 用 id 兜底保证顺序稳定。</p>
     */
    @Select("""
            SELECT s.*
              FROM im_session s
              JOIN im_session_member m ON m.session_id = s.id
             WHERE m.user_id = #{userId}
             ORDER BY s.last_msg_at DESC, s.id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<ImSession> listByUser(@Param("userId") Long userId,
                               @Param("offset") int offset,
                               @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM im_session s
              JOIN im_session_member m ON m.session_id = s.id
             WHERE m.user_id = #{userId}
            """)
    long countByUser(@Param("userId") Long userId);

    /**
     * 批量取每个会话的成员数。
     *
     * <p>群聊会话列表要显示「N 人」，逐条 count 就是 N+1；一次 GROUP BY 把
     * N 次查询压成 1 次。走 {@code im_session_member} 的主键前缀（session_id 在最左），
     * 每个会话是一次索引范围扫描，不回表。</p>
     */
    @Select("""
            <script>
            SELECT session_id AS sessionId, COUNT(*) AS memberCount
              FROM im_session_member
             WHERE session_id IN
             <foreach collection="sessionIds" item="sid" open="(" separator="," close=")">
               #{sid}
             </foreach>
             GROUP BY session_id
            </script>
            """)
    List<MemberCountRow> countMembers(@Param("sessionIds") List<Long> sessionIds);

    class MemberCountRow {
        private Long sessionId;
        private Integer memberCount;

        public Long getSessionId() {
            return sessionId;
        }

        public void setSessionId(Long sessionId) {
            this.sessionId = sessionId;
        }

        public Integer getMemberCount() {
            return memberCount;
        }

        public void setMemberCount(Integer memberCount) {
            this.memberCount = memberCount;
        }
    }
}

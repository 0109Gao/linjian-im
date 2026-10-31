package com.linjian.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linjian.im.chat.entity.ImInbox;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ImInboxMapper extends BaseMapper<ImInbox> {

    /**
     * 写入离线未读。
     *
     * <p>用 INSERT IGNORE 而不是普通 INSERT：同一条消息对同一个用户重复投递时
     * （例如重试、或在线判定抖动导致既推了又写了），撞唯一键应当静默无操作，
     * 而不是抛异常打断投递循环——一个人收不到不应该影响其他人收货。</p>
     */
    @Insert("""
            INSERT IGNORE INTO im_inbox (id, user_id, session_id, seq, created_at)
            VALUES (#{id}, #{userId}, #{sessionId}, #{seq}, NOW())
            """)
    int insertIgnore(ImInbox inbox);

    /** 未读数。走 uk_user_session_seq 的最左前缀，不需要回表。 */
    @Select("SELECT COUNT(*) FROM im_inbox WHERE user_id = #{userId} AND session_id = #{sessionId}")
    long countUnread(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    /** 会话列表用：一次拿到该用户全部会话的未读数，避免每行一次查询。 */
    @Select("""
            SELECT session_id AS sessionId, COUNT(*) AS unread
              FROM im_inbox
             WHERE user_id = #{userId}
             GROUP BY session_id
            """)
    List<UnreadRow> countUnreadGrouped(@Param("userId") Long userId);

    /**
     * 标记已读：删掉 seq 小于等于游标的未读行。
     *
     * <p>这就是「收件箱即未读集合」的实现——已读即删除，表里剩下的永远只是未读。
     * 好处是未读数不需要任何公式推导，{@code COUNT(*)} 就是答案，
     * 也不会被 seq 空洞干扰。</p>
     */
    @Delete("""
            DELETE FROM im_inbox
             WHERE user_id = #{userId} AND session_id = #{sessionId} AND seq <= #{seq}
            """)
    int deleteUpTo(@Param("userId") Long userId,
                   @Param("sessionId") Long sessionId,
                   @Param("seq") long seq);

    /**
     * 兜底清理：删掉超期未读的残留。
     *
     * <p>限了 LIMIT，因为一次删除几十万行会长时间持锁并撑大 undo log。
     * 定时任务每轮删一批，反复几轮即可清完。</p>
     */
    @Delete("""
            DELETE FROM im_inbox
             WHERE created_at < #{deadline}
             LIMIT #{limit}
            """)
    int deleteBefore(@Param("deadline") LocalDateTime deadline, @Param("limit") int limit);

    class UnreadRow {
        private Long sessionId;
        private Long unread;

        public Long getSessionId() {
            return sessionId;
        }

        public void setSessionId(Long sessionId) {
            this.sessionId = sessionId;
        }

        public Long getUnread() {
            return unread;
        }

        public void setUnread(Long unread) {
            this.unread = unread;
        }
    }
}

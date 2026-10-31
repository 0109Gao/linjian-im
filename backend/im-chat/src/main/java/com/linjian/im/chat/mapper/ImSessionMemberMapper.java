package com.linjian.im.chat.mapper;

import com.linjian.im.chat.entity.ImSessionMember;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 成员表用 (session_id, user_id) 联合主键，因此不继承 {@code BaseMapper}：
 * 通用 CRUD 依赖单列主键，套用在这里只会生成错误的 where 条件。
 * 需要的三个操作全部用注解 SQL 显式写出。
 */
@Mapper
public interface ImSessionMemberMapper {

    @Insert("INSERT IGNORE INTO im_session_member (session_id, user_id, joined_at) "
            + "VALUES (#{sessionId}, #{userId}, NOW())")
    int insertIgnore(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    /** 鉴权用：这个人是不是这个会话的成员。走主键，一次等值查询。 */
    @Select("SELECT COUNT(*) FROM im_session_member WHERE session_id = #{sessionId} AND user_id = #{userId}")
    int exists(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    /** 投递用：这个会话的全部成员，用来决定推给谁、给谁写离线收件箱。 */
    @Select("SELECT user_id FROM im_session_member WHERE session_id = #{sessionId}")
    List<Long> listUserIds(@Param("sessionId") Long sessionId);

    /**
     * 批量入群。一条 INSERT IGNORE ... VALUES (...),(...) 顶掉 N 次单条插入。
     *
     * <p>建一个 200 人群如果用循环单插，就是 200 次网络往返；批量插入是 1 次。
     * IGNORE 保证重复邀请同一个人时静默跳过——幂等在这里比「报告冲突」有用得多。</p>
     */
    @Insert("""
            <script>
            INSERT IGNORE INTO im_session_member (session_id, user_id, joined_at) VALUES
            <foreach collection="userIds" item="uid" separator=",">
              (#{sessionId}, #{uid}, NOW())
            </foreach>
            </script>
            """)
    int insertBatch(@Param("sessionId") Long sessionId, @Param("userIds") List<Long> userIds);

    /** 退群。返回 0 表示本来就不在群里（并发重复退群时属于正常结果）。 */
    @Delete("DELETE FROM im_session_member WHERE session_id = #{sessionId} AND user_id = #{userId}")
    int delete(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM im_session_member WHERE session_id = #{sessionId}")
    int countMembers(@Param("sessionId") Long sessionId);

    /** 群成员列表。一次 join 取昵称，避免每行再查一次用户表。 */
    @Select("""
            SELECT m.user_id AS userId, u.nickname AS nickname, m.joined_at AS joinedAt
              FROM im_session_member m
              JOIN im_user u ON u.id = m.user_id
             WHERE m.session_id = #{sessionId}
             ORDER BY m.joined_at ASC, m.user_id ASC
            """)
    List<MemberRow> listMembers(@Param("sessionId") Long sessionId);

    /**
     * 批量取每个会话的「对方」。
     *
     * <p>会话列表如果逐条查对方昵称就是标准 N+1，一次 IN 查询把 N 次压成 1 次。
     * 单聊语义下对方一定是唯一那个 {@code user_id <> 我} 的成员。</p>
     *
     * <p><b>调用方只能传单聊会话的 id。</b>混进群聊会产生 199 行「对方」，
     * 按 sessionId 塞进 Map 时互相覆盖，最后展示出群里某个随机成员的昵称。
     * W8 的 {@code SessionService.list} 因此在调用前把群聊 id 滤掉了。</p>
     */
    @Select("""
            <script>
            SELECT m.session_id AS sessionId, u.id AS userId, u.nickname AS nickname
              FROM im_session_member m
              JOIN im_user u ON u.id = m.user_id
             WHERE m.session_id IN
             <foreach collection="sessionIds" item="sid" open="(" separator="," close=")">
               #{sid}
             </foreach>
               AND m.user_id &lt;&gt; #{selfId}
            </script>
            """)
    List<SessionPeerRow> listPeers(@Param("sessionIds") List<Long> sessionIds,
                                   @Param("selfId") Long selfId);

    /** 投影结果。字段名与 SQL 的别名一致，靠 map-underscore-to-camel-case 无关，直接按别名匹配。 */
    class SessionPeerRow {
        private Long sessionId;
        private Long userId;
        private String nickname;

        public Long getSessionId() {
            return sessionId;
        }

        public void setSessionId(Long sessionId) {
            this.sessionId = sessionId;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }
    }

    /** 群成员行。{@code joinedAt} 用于稳定排序，也让客户端能显示「谁先来的」。 */
    class MemberRow {
        private Long userId;
        private String nickname;
        private LocalDateTime joinedAt;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }

        public LocalDateTime getJoinedAt() {
            return joinedAt;
        }

        public void setJoinedAt(LocalDateTime joinedAt) {
            this.joinedAt = joinedAt;
        }
    }
}

package com.linjian.im.chat.mapper;

import com.linjian.im.chat.entity.ImSessionMember;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
     * 批量取每个会话的「对方」。
     *
     * <p>会话列表如果逐条查对方昵称就是标准 N+1，一次 IN 查询把 N 次压成 1 次。
     * 单聊语义下对方一定是唯一那个 {@code user_id <> 我} 的成员；群聊在 W8
     * 需要换成另一套查询（返回群名与成员数），届时这里会被替换而不是复用。</p>
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
}

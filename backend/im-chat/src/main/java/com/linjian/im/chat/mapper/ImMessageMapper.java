package com.linjian.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linjian.im.chat.entity.ImMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ImMessageMapper extends BaseMapper<ImMessage> {

    /**
     * 增量补齐：断线重连或首次进入会话后拉「我还没看到的」。
     * 走 uk_session_seq 的范围扫描，seq 有空洞不影响结果。
     */
    @Select("""
            SELECT * FROM im_message
             WHERE session_id = #{sessionId} AND seq > #{afterSeq}
             ORDER BY seq ASC
             LIMIT #{limit}
            """)
    List<ImMessage> listAfter(@Param("sessionId") Long sessionId,
                              @Param("afterSeq") long afterSeq,
                              @Param("limit") int limit);

    /**
     * 向上翻历史：拿比 beforeSeq 更早的 limit 条。
     * 用 DESC 取最近的一页再在服务层反转，否则「取一页最早的」会把整段历史都扫出来。
     */
    @Select("""
            SELECT * FROM im_message
             WHERE session_id = #{sessionId} AND seq < #{beforeSeq}
             ORDER BY seq DESC
             LIMIT #{limit}
            """)
    List<ImMessage> listBefore(@Param("sessionId") Long sessionId,
                               @Param("beforeSeq") long beforeSeq,
                               @Param("limit") int limit);

    /** 首次进入会话：最近 limit 条，同样 DESC 取再反转。 */
    @Select("""
            SELECT * FROM im_message
             WHERE session_id = #{sessionId}
             ORDER BY seq DESC
             LIMIT #{limit}
            """)
    List<ImMessage> listLatest(@Param("sessionId") Long sessionId, @Param("limit") int limit);

    /**
     * 幂等回查：客户端重发同一个 clientMsgId 时，用它找出首次落库的那条，
     * 把原始 seq 原样返回，避免客户端重复显示同一条消息。
     */
    @Select("""
            SELECT * FROM im_message
             WHERE sender_id = #{senderId} AND client_msg_id = #{clientMsgId}
            """)
    ImMessage selectByClientMsgId(@Param("senderId") Long senderId,
                                  @Param("clientMsgId") String clientMsgId);

    /**
     * 管理端概览用的总数。
     *
     * <p>这是唯一一个注定会随数据量线性变慢的查询：{@code COUNT(*)} 在 InnoDB 上
     * 没有缓存值可用，必须扫一遍最小的那个索引。30 万行实测几十毫秒（见
     * {@code docs/性能验证.md}），对「管理员打开首页看一个数字」这个场景足够；
     * 如果哪天不够，正确的做法是改成定时任务算好写进一张统计表，
     * 而不是加缓存——统计值本来就不需要精确到秒。</p>
     */
    @Select("SELECT COUNT(*) FROM im_message")
    long countAll();

    @Select("SELECT COUNT(*) FROM im_message WHERE created_at >= #{from}")
    long countSince(@Param("from") LocalDateTime from);

    /**
     * 每个会话当前的「已用到的最大序号」，用于从库里重建 Redis 计数。
     *
     * <p>走 {@code uk_session_seq (session_id, seq)} 的松散索引扫描：InnoDB 对
     * {@code GROUP BY session_id} + {@code MAX(seq)} 可以只跳着读每个会话索引末端，
     * 不需要回表也不需要排序。会话数为万级时仍是百毫秒内的事，而它只在启动时跑一次。</p>
     *
     * <p>没有任何消息的会话不会出现在结果里——它们的计数本来就该从 0 开始，
     * 缺失即正确。</p>
     */
    @Select("""
            SELECT session_id AS sessionId, MAX(seq) AS maxSeq
              FROM im_message
             GROUP BY session_id
            """)
    List<SessionSeqRow> maxSeqBySession();

    /** 重建计数的输入行。字段名与别名一一对应，靠 map-underscore-to-camel-case 之外的显式别名映射。 */
    @lombok.Data
    class SessionSeqRow {
        private Long sessionId;
        private Long maxSeq;
    }
}

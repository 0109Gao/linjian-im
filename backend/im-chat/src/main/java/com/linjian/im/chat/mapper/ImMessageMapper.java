package com.linjian.im.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linjian.im.chat.entity.ImMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}

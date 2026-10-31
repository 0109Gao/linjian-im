package com.linjian.im.chat.service;

import com.linjian.im.chat.dto.ReadResult;
import com.linjian.im.chat.mapper.ImInboxMapper;
import com.linjian.im.chat.mapper.ImReadCursorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 已读上报。
 *
 * <p>为什么走 REST 而不是 STOMP：判断依据是「这个操作丢了会怎样」。
 * 发消息丢了客户端能靠 ack 超时发现并重发，而已读上报在 STOMP 上是发出去就不管
 * （SimpleBroker 没有投递回执），丢了没有任何信号，用户表现为「红点不消」。
 * REST 至少有状态码可以重试，也能被前端统一拦截处理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadService {

    private final ImReadCursorMapper cursorMapper;
    private final ImInboxMapper inboxMapper;
    private final SessionService sessionService;

    /**
     * 上报已读到 {@code lastReadSeq}，并删掉收件箱里对应的未读行。
     *
     * <p>游标写入用 {@code GREATEST}、删除用 {@code seq <= lastReadSeq}，
     * 两者对乱序上报都是安全的：先到 10 再到 8 时，游标保持 10，
     * 第二次删除影响 0 行。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public ReadResult markRead(Long userId, Long sessionId, long lastReadSeq) {
        sessionService.assertMember(sessionId, userId);
        cursorMapper.upsert(sessionId, userId, lastReadSeq);
        int deleted = inboxMapper.deleteUpTo(userId, sessionId, lastReadSeq);
        long unread = inboxMapper.countUnread(userId, sessionId);
        log.debug("已读上报 sessionId={} userId={} lastReadSeq={} 清除未读={} 剩余={}",
                sessionId, userId, lastReadSeq, deleted, unread);
        return new ReadResult(sessionId, lastReadSeq, unread);
    }
}

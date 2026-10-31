package com.linjian.im.chat.service;

import com.linjian.im.chat.dto.MessagePage;
import com.linjian.im.chat.dto.MessageView;
import com.linjian.im.chat.dto.SendCommand;
import com.linjian.im.chat.dto.SendResult;
import com.linjian.im.chat.entity.ImInbox;
import com.linjian.im.chat.entity.ImMessage;
import com.linjian.im.chat.entity.ImSession;
import com.linjian.im.chat.mapper.ImInboxMapper;
import com.linjian.im.chat.mapper.ImMessageMapper;
import com.linjian.im.chat.mapper.ImSessionMapper;
import com.linjian.im.chat.mapper.ImSessionMemberMapper;
import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.exception.BusinessException;
import com.linjian.im.common.id.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 消息服务：发送、幂等、投递、拉取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    /** 会话列表里展示的摘要长度（按字符计，不是字节）。 */
    private static final int PREVIEW_CODE_POINTS = 30;

    private static final int DEFAULT_PAGE = 30;
    private static final int MAX_PAGE = 100;

    private final ImMessageMapper messageMapper;
    private final ImSessionMapper sessionMapper;
    private final ImSessionMemberMapper memberMapper;
    private final ImInboxMapper inboxMapper;
    private final SeqService seqService;
    private final ContentValidator contentValidator;
    private final PresenceService presenceService;
    private final MessageDelivery delivery;
    private final SessionService sessionService;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 发送一条消息。
     *
     * <p>顺序是刻意的：先鉴权、再校验内容、再查重，最后才分配 seq。
     * 如果先分配 seq，每一条被拒绝的请求都会白白消耗一个号，
     * 让「seq 空洞」从「罕见故障」变成「被非法请求批量制造」。</p>
     *
     * <p>方法不加 {@code @Transactional}：一是各步骤本身幂等，二是推送发生在落库之后，
     * 若包在事务里，事务尚未提交就推送，客户端可能收到一条库里最终不存在的消息。
     * 宁可接受「落库成功但推送失败」——那种情况客户端靠 seq 拉取可以自查补回，
     * 反过来则无法自愈。</p>
     */
    public SendResult send(Long senderId, SendCommand cmd) {
        if (cmd == null || cmd.sessionId() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "sessionId 不能为空");
        }
        if (cmd.clientMsgId() == null || cmd.clientMsgId().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "clientMsgId 不能为空，它是重发去重的唯一依据");
        }
        if (cmd.clientMsgId().length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "clientMsgId 不能超过 64 字符");
        }

        // ① 成员鉴权
        sessionService.assertMember(cmd.sessionId(), senderId);
        // ② 内容校验
        String content = contentValidator.requireValid(cmd.content());
        // ③ 去重：重发直接回放首次结果
        ImMessage duplicated = messageMapper.selectByClientMsgId(senderId, cmd.clientMsgId());
        if (duplicated != null) {
            log.debug("命中重发 clientMsgId={} 原 seq={}", cmd.clientMsgId(), duplicated.getSeq());
            return SendResult.ok(MessageView.of(duplicated), true);
        }

        // ④ 分配 seq（此后的失败会留下空洞）
        long seq = seqService.next(cmd.sessionId());

        // ⑤ 落库
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        ImMessage message = new ImMessage();
        message.setId(idGenerator.nextId());
        message.setSessionId(cmd.sessionId());
        message.setSeq(seq);
        message.setSenderId(senderId);
        message.setContentType(ImMessage.CONTENT_TYPE_TEXT);
        message.setContent(content);
        message.setClientMsgId(cmd.clientMsgId());
        message.setCreatedAt(now);
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            // 并发重发：两条请求同时通过了第 ③ 步，第二条在这里撞上 uk_sender_client。
            // 回查并把首次的结果返回，客户端看到的仍然是「一条消息」。
            ImMessage exist = messageMapper.selectByClientMsgId(senderId, cmd.clientMsgId());
            if (exist != null) {
                log.debug("并发重发被唯一索引拦下 clientMsgId={} 原 seq={}", cmd.clientMsgId(), exist.getSeq());
                return SendResult.ok(MessageView.of(exist), true);
            }
            log.error("消息落库冲突但回查不到原消息 seq={} clientMsgId={}", seq, cmd.clientMsgId(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "消息保存失败");
        }

        // ⑥ 更新会话的「最后一条消息」冗余字段
        sessionMapper.touchLastMessage(cmd.sessionId(), seq, now, preview(content));

        // ⑦ 投递
        MessageView view = MessageView.of(message);
        deliver(cmd.sessionId(), senderId, view);

        log.debug("消息已发送 sessionId={} seq={} sender={}", cmd.sessionId(), seq, senderId);
        return SendResult.ok(view, false);
    }

    /**
     * 投递。两条路，按会话类型分：
     *
     * <ul>
     *   <li><b>单聊</b>：给非发送者写未读行，然后给<i>所有</i>在线成员逐个推
     *       {@code /user/queue/message}。发送者自己也推——多端场景下他的其他设备
     *       必须知道这条消息，否则那台设备要等下次拉取才看得到。</li>
     *   <li><b>群聊</b>：未读行照样写（消息进哪个群由成员表决定，与在线无关），
     *       但推送改成向 {@code /topic/session.{id}} 广播一次。200 人群逐个推意味着
     *       200 次序列化 + 200 次 Redis 在线判断；广播天然只送给「连着且订阅了」的人，
     *       在线判断这一步直接消失。</li>
     * </ul>
     *
     * <p><b>为什么所有成员都写未读行（包括在线的）：</b>「推到了连接上」不等于
     * 「人看过了」。用户在线但没打开这个会话时，消息只是躺在连接里，若不写未读行，
     * 未读数永远是 0，红点不会亮。代价是每条消息多一次批量 INSERT（一条 SQL），
     * 换来的是未读数始终准确——而红点是 IM 最核心的交互信号。</p>
     *
     * <p><b>发送者不写未读行：</b>自己发的消息不该给自己亮红点。但推送照给，
     * 客户端按 {@code clientMsgId} 把服务端回推和自己本地那条乐观插入认成同一条，
     * 用服务端返回的 seq 覆盖本地的临时值。</p>
     */
    private void deliver(Long sessionId, Long senderId, MessageView view) {
        List<Long> members = memberMapper.listUserIds(sessionId);

        List<ImInbox> rows = new ArrayList<>(members.size());
        for (Long memberId : members) {
            if (memberId.equals(senderId)) {
                continue;
            }
            ImInbox inbox = new ImInbox();
            inbox.setId(idGenerator.nextId());
            inbox.setUserId(memberId);
            inbox.setSessionId(sessionId);
            inbox.setSeq(view.seq());
            rows.add(inbox);
        }
        if (!rows.isEmpty()) {
            inboxMapper.insertBatch(rows);
        }

        if (isGroup(sessionId)) {
            delivery.deliverToSession(sessionId, view);
            return;
        }
        for (Long memberId : members) {
            if (presenceService.isOnline(memberId)) {
                delivery.deliverToUser(memberId, view);
            }
        }
    }

    /**
     * 会话类型只在这里用一次，因此不缓存、也不放进 SendCommand。
     *
     * <p>一次主键查询（走聚簇索引，命中 buffer pool 后是纯内存操作）换掉群聊路径上
     * 200 次 Redis 在线判断，这笔账很清楚。查不到会话时按单聊处理——那种情况
     * 只会出现在会话被并发删除的窗口里，走点对点推送至少不会丢消息。</p>
     */
    private boolean isGroup(Long sessionId) {
        ImSession session = sessionMapper.selectById(sessionId);
        return session != null
                && session.getSessionType() != null
                && session.getSessionType() == ImSession.TYPE_GROUP;
    }

    /**
     * 拉取消息。三个方向互斥，按优先级取一个：
     * <ul>
     *   <li>{@code afterSeq}：断线重连或首屏之后的增量补齐，升序返回</li>
     *   <li>{@code beforeSeq}：向上翻历史，降序取一页后在服务端反转成升序</li>
     *   <li>都不传：最近一页</li>
     * </ul>
     */
    public MessagePage list(Long userId, Long sessionId, Long afterSeq, Long beforeSeq, Integer limit) {
        sessionService.assertMember(sessionId, userId);
        int size = limit == null ? DEFAULT_PAGE : Math.min(Math.max(limit, 1), MAX_PAGE);
        int probe = size + 1;

        List<ImMessage> raw;
        if (afterSeq != null) {
            raw = messageMapper.listAfter(sessionId, afterSeq, probe);
        } else if (beforeSeq != null) {
            raw = new ArrayList<>(messageMapper.listBefore(sessionId, beforeSeq, probe));
            Collections.reverse(raw);
        } else {
            raw = new ArrayList<>(messageMapper.listLatest(sessionId, probe));
            Collections.reverse(raw);
        }

        boolean hasMore = raw.size() > size;
        if (hasMore) {
            raw = raw.subList(raw.size() - size, raw.size());
        }
        return new MessagePage(raw.stream().map(MessageView::of).toList(), hasMore);
    }

    /**
     * 生成会话列表摘要。按字符截断，避免把一个 emoji 劈成两半存进库——
     * {@code substring} 按 char 索引切会切出半个代理对，落库后就是乱码。
     */
    private String preview(String content) {
        int codePoints = content.codePointCount(0, content.length());
        if (codePoints <= PREVIEW_CODE_POINTS) {
            return content;
        }
        int end = content.offsetByCodePoints(0, PREVIEW_CODE_POINTS);
        return content.substring(0, end) + "…";
    }
}

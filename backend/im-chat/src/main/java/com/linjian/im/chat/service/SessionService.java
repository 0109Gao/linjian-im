package com.linjian.im.chat.service;

import com.linjian.im.chat.dto.PageView;
import com.linjian.im.chat.dto.SessionView;
import com.linjian.im.chat.entity.ImSession;
import com.linjian.im.chat.entity.ImUser;
import com.linjian.im.chat.mapper.ImInboxMapper;
import com.linjian.im.chat.mapper.ImSessionMapper;
import com.linjian.im.chat.mapper.ImSessionMemberMapper;
import com.linjian.im.chat.mapper.ImUserMapper;
import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.exception.BusinessException;
import com.linjian.im.common.id.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话服务：懒创建、成员鉴权、会话列表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final ImSessionMapper sessionMapper;
    private final ImSessionMemberMapper memberMapper;
    private final ImInboxMapper inboxMapper;
    private final ImUserMapper userMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final PresenceService presenceService;

    /**
     * 打开单聊会话，不存在则创建。
     *
     * <p>并发安全靠 {@code biz_key} 的唯一索引 + {@code INSERT IGNORE}，而不是应用层加锁：
     * 两个人同时点开对方时，两边都会拼出同一个 bizKey，其中一方插入成功，
     * 另一方插入影响行数为 0，随后统一走「按 bizKey 回查」拿到同一个会话。
     * 结果是不管谁先谁后，双方拿到的 sessionId 一定相同。</p>
     *
     * <p>方法刻意不加 {@code @Transactional}：中间步骤都是幂等的，加事务反而会让
     * 「插入影响 0 行」这种正常分支在事务语义下变得需要额外考虑可见性。</p>
     */
    public Long openSingle(Long selfId, Long peerId) {
        if (peerId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "peerUserId 不能为空");
        }
        if (selfId.equals(peerId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "不能和自己建立会话");
        }
        ImUser peer = userMapper.selectById(peerId);
        if (peer == null || peer.getStatus() == null || peer.getStatus() != 1) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "对方账号不存在或已被禁用");
        }

        String bizKey = selfId < peerId ? selfId + ":" + peerId : peerId + ":" + selfId;
        ImSession existing = sessionMapper.selectByBizKey(bizKey);
        if (existing != null) {
            // 幂等补齐：即使上次建会话时只写进了一个成员，这里也能修好
            memberMapper.insertIgnore(existing.getId(), selfId);
            memberMapper.insertIgnore(existing.getId(), peerId);
            return existing.getId();
        }

        ImSession session = new ImSession();
        session.setId(idGenerator.nextId());
        session.setSessionType(ImSession.TYPE_SINGLE);
        session.setBizKey(bizKey);
        sessionMapper.insertIgnore(session);

        // 无论刚才是自己插进去的还是别人抢先插的，一律回查，避免拿到一个没落库的 id
        ImSession saved = sessionMapper.selectByBizKey(bizKey);
        if (saved == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "会话创建失败");
        }
        memberMapper.insertIgnore(saved.getId(), selfId);
        memberMapper.insertIgnore(saved.getId(), peerId);
        log.info("单聊会话就绪 sessionId={} 发起人={} 对方={}", saved.getId(), selfId, peerId);
        return saved.getId();
    }

    /**
     * 成员鉴权。消息收发、历史拉取、已读上报三处都必须先过这一关。
     *
     * <p>正常路径只查一次成员表（走主键）；只有不在会话里时才多查一次会话表，
     * 用来区分「会话根本不存在」和「会话存在但你不是成员」——这两个错误对排查
     * 的意义完全不同，对客户端也不该混成一个。</p>
     */
    public void assertMember(Long sessionId, Long userId) {
        if (sessionId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "sessionId 不能为空");
        }
        if (memberMapper.exists(sessionId, userId) > 0) {
            return;
        }
        if (sessionMapper.selectById(sessionId) == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        throw new BusinessException(ErrorCode.NOT_IN_SESSION);
    }

    /**
     * 会话列表。四份数据一次批量取齐，避免 N+1：
     * 会话本体（join 成员表）、对方信息（一次 IN 查询）、未读数（一次 GROUP BY）、在线状态。
     */
    public PageView<SessionView> list(Long userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * safeSize;

        long total = sessionMapper.countByUser(userId);
        List<ImSession> sessions = sessionMapper.listByUser(userId, offset, safeSize);
        if (sessions.isEmpty()) {
            return new PageView<>(List.of(), total);
        }

        List<Long> sessionIds = sessions.stream().map(ImSession::getId).toList();

        Map<Long, ImSessionMemberMapper.SessionPeerRow> peers = new HashMap<>();
        for (ImSessionMemberMapper.SessionPeerRow row : memberMapper.listPeers(sessionIds, userId)) {
            peers.put(row.getSessionId(), row);
        }

        Map<Long, Long> unreadMap = new HashMap<>();
        for (ImInboxMapper.UnreadRow row : inboxMapper.countUnreadGrouped(userId)) {
            unreadMap.put(row.getSessionId(), row.getUnread());
        }

        List<SessionView> views = new ArrayList<>(sessions.size());
        for (ImSession s : sessions) {
            ImSessionMemberMapper.SessionPeerRow peer = peers.get(s.getId());
            Long peerId = peer == null ? null : peer.getUserId();
            String peerNickname = peer == null ? "未知用户" : peer.getNickname();
            boolean online = peerId != null && presenceService.isOnline(peerId);
            views.add(new SessionView(
                    s.getId(),
                    peerId,
                    peerNickname,
                    online,
                    unreadMap.getOrDefault(s.getId(), 0L),
                    s.getLastMsgSeq(),
                    s.getLastMsgAt(),
                    s.getLastMsgPreview()
            ));
        }
        return new PageView<>(views, total);
    }
}

package com.linjian.im.chat.service;

import com.linjian.im.chat.dto.MemberView;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话服务：单聊懒创建、群聊生命周期、成员鉴权、会话列表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    /** 群成员上限（含群主）。200 是「一次广播能覆盖、一张成员表能一次查完」的量级。 */
    public static final int MAX_GROUP_MEMBERS = 200;

    /** 群名长度上限，按 Unicode 码点计，与消息内容的校验口径一致。 */
    public static final int MAX_GROUP_NAME_CODE_POINTS = 64;

    private static final int SINGLE_MEMBER_COUNT = 2;

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
     * 建群。创建者自动成为成员，且不计入 {@code memberIds}。
     *
     * <p>成员一次性批量插入（一条 INSERT ... VALUES (...),(...)），不是循环单插：
     * 建 200 人群时那将是 200 次往返。</p>
     *
     * <p><b>不做群主概念。</b>W8 只有「成员」一种角色，谁都能拉人、谁都能退群。
     * 真实产品需要群主 / 管理员 / 转让，那套东西的价值完全建立在「有人能踢人」之上，
     * 而踢人又要求在线连接立刻失效——这一整条链路不在 W8 的范围内。
     * 只做成员的最大好处是：没有「群主退群后群归谁」这种无解状态。</p>
     */
    public Long createGroup(Long selfId, String rawName, List<Long> memberIds) {
        String name = requireValidGroupName(rawName);
        List<Long> invitees = distinctOthers(selfId, memberIds);
        if (invitees.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "至少要邀请一个成员");
        }
        if (invitees.size() + 1 > MAX_GROUP_MEMBERS) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "群成员上限 " + MAX_GROUP_MEMBERS + " 人（含自己）");
        }
        assertUsersUsable(invitees);

        ImSession session = new ImSession();
        session.setId(idGenerator.nextId());
        session.setSessionType(ImSession.TYPE_GROUP);
        // biz_key 保持 NULL：唯一索引允许多个 NULL，因此群聊之间不会互相冲突，
        // 也不会和「min:max」格式的单聊键撞上。
        session.setBizKey(null);
        session.setName(name);
        sessionMapper.insertIgnore(session);

        List<Long> allMembers = new ArrayList<>(invitees.size() + 1);
        allMembers.add(selfId);
        allMembers.addAll(invitees);
        int inserted = memberMapper.insertBatch(session.getId(), allMembers);

        log.info("群聊已创建 sessionId={} 群名={} 群主={} 成员数={} 实际写入={}",
                session.getId(), name, selfId, allMembers.size(), inserted);
        return session.getId();
    }

    /**
     * 拉人入群。已在群里的人被静默跳过，返回值是真正新增的人数。
     *
     * <p>为什么允许「任何成员」拉人而不是只有群主：见 {@link #createGroup} 的说明，
     * W8 没有角色。留在文档里的风险是明确的——任何人拉人后群里所有人都会看到新成员，
     * 但没有审批环节。</p>
     */
    public int addMembers(Long selfId, Long sessionId, List<Long> userIds) {
        requireGroup(sessionId);
        assertMember(sessionId, selfId);

        List<Long> wanted = distinctOthers(null, userIds);
        if (wanted.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "userIds 不能为空");
        }
        Set<Long> existing = new LinkedHashSet<>(memberMapper.listUserIds(sessionId));
        List<Long> toAdd = wanted.stream().filter(id -> !existing.contains(id)).toList();
        if (toAdd.isEmpty()) {
            return 0;
        }
        if (existing.size() + toAdd.size() > MAX_GROUP_MEMBERS) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "群成员上限 " + MAX_GROUP_MEMBERS + " 人，当前 " + existing.size()
                            + " 人，本次要新增 " + toAdd.size() + " 人");
        }
        assertUsersUsable(toAdd);

        memberMapper.insertBatch(sessionId, toAdd);
        log.info("群聊拉人 sessionId={} 操作人={} 新增={} 人数={}",
                sessionId, selfId, toAdd.size(), existing.size() + toAdd.size());
        return toAdd.size();
    }

    /**
     * 退群。返回剩余人数。
     *
     * <p>最后一个人退出后<b>不删会话</b>。删会话要连带删消息、收件箱、已读游标，
     * 而群消息本身是历史资产（万一有人重新被拉进来，旧消息是否该可见是个产品问题）。
     * 这里选择留下一个空群会话，由后续的清理任务决定怎么处理，而不是在这一步
     * 做一个无法回滚的级联删除。</p>
     */
    public int leaveGroup(Long selfId, Long sessionId) {
        requireGroup(sessionId);
        assertMember(sessionId, selfId);

        memberMapper.delete(sessionId, selfId);
        int remaining = memberMapper.countMembers(sessionId);
        log.info("退群 sessionId={} userId={} 剩余={}", sessionId, selfId, remaining);
        return remaining;
    }

    /**
     * 群成员列表。在线状态一次 pipeline 取齐，不逐个查 Redis。
     */
    public List<MemberView> members(Long viewerId, Long sessionId) {
        assertMember(sessionId, viewerId);
        List<ImSessionMemberMapper.MemberRow> rows = memberMapper.listMembers(sessionId);
        Set<Long> online = presenceService.filterOnline(
                rows.stream().map(ImSessionMemberMapper.MemberRow::getUserId).toList());
        List<MemberView> views = new ArrayList<>(rows.size());
        for (ImSessionMemberMapper.MemberRow row : rows) {
            views.add(new MemberView(row.getUserId(), row.getNickname(),
                    online.contains(row.getUserId()), row.getJoinedAt()));
        }
        return views;
    }

    /**
     * 成员鉴权。消息收发、历史拉取、已读上报、订阅群主题四处都必须先过这一关。
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
     * 会话本体（join 成员表）、单聊对方信息（一次 IN 查询）、成员数（一次 GROUP BY）、
     * 未读数（一次 GROUP BY）、在线状态。
     *
     * <p>群聊不参与「对方」查询——{@code listPeers} 对群聊会返回 199 行，
     * 按 sessionId 进 Map 时互相覆盖，最后显示的是群里某个随机成员的昵称。
     * 因此先把会话按类型分开，只拿单聊 id 去查对方。</p>
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
        List<Long> singleIds = sessions.stream()
                .filter(SessionService::isSingle)
                .map(ImSession::getId)
                .toList();

        Map<Long, ImSessionMemberMapper.SessionPeerRow> peers = new HashMap<>();
        if (!singleIds.isEmpty()) {
            for (ImSessionMemberMapper.SessionPeerRow row : memberMapper.listPeers(singleIds, userId)) {
                peers.put(row.getSessionId(), row);
            }
        }

        Map<Long, Integer> memberCounts = new HashMap<>();
        for (ImSessionMapper.MemberCountRow row : sessionMapper.countMembers(sessionIds)) {
            memberCounts.put(row.getSessionId(), row.getMemberCount());
        }

        Map<Long, Long> unreadMap = new HashMap<>();
        for (ImInboxMapper.UnreadRow row : inboxMapper.countUnreadGrouped(userId)) {
            unreadMap.put(row.getSessionId(), row.getUnread());
        }

        List<SessionView> views = new ArrayList<>(sessions.size());
        for (ImSession s : sessions) {
            boolean group = !isSingle(s);
            ImSessionMemberMapper.SessionPeerRow peer = group ? null : peers.get(s.getId());
            Long peerId = peer == null ? null : peer.getUserId();
            String peerNickname = group ? null : (peer == null ? "未知用户" : peer.getNickname());
            boolean online = !group && peerId != null && presenceService.isOnline(peerId);
            int memberCount = group
                    ? memberCounts.getOrDefault(s.getId(), 0)
                    : SINGLE_MEMBER_COUNT;

            views.add(new SessionView(
                    s.getId(),
                    s.getSessionType() == null ? ImSession.TYPE_SINGLE : s.getSessionType(),
                    s.getName(),
                    peerId,
                    peerNickname,
                    online,
                    memberCount,
                    unreadMap.getOrDefault(s.getId(), 0L),
                    s.getLastMsgSeq(),
                    s.getLastMsgAt(),
                    s.getLastMsgPreview()
            ));
        }
        return new PageView<>(views, total);
    }

    // ------------------------------------------------------------------
    // 内部校验
    // ------------------------------------------------------------------

    private static boolean isSingle(ImSession s) {
        return s.getSessionType() == null || s.getSessionType() == ImSession.TYPE_SINGLE;
    }

    /** 确认会话存在且是群聊。单聊走这里的错误是「参数不合法」而不是 404。 */
    private ImSession requireGroup(Long sessionId) {
        if (sessionId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "sessionId 不能为空");
        }
        ImSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        if (isSingle(session)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该会话不是群聊");
        }
        return session;
    }

    /**
     * 群名校验：按码点算长度，去掉首尾空白后再判断。
     *
     * <p>不用 {@code String.length()} 是因为它数 UTF-16 单元——64 个 emoji 会被算成 128
     * 而误判超长。同一个坑在消息内容校验里已经踩过一次。</p>
     */
    private String requireValidGroupName(String raw) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "群名不能为空");
        }
        String name = raw.strip();
        int codePoints = name.codePointCount(0, name.length());
        if (codePoints == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "群名不能为空");
        }
        if (codePoints > MAX_GROUP_NAME_CODE_POINTS) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "群名不能超过 " + MAX_GROUP_NAME_CODE_POINTS + " 个字符");
        }
        return name;
    }

    /**
     * 去重 + 去掉 null + 可选地去掉自己。
     *
     * <p>用 {@link LinkedHashSet} 而不是 {@code HashSet}：错误信息里报「第几个用户」
     * 时顺序要稳定，否则同一个请求两次调用给出的提示不一样，排查时无法复现。</p>
     */
    private List<Long> distinctOthers(Long selfId, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : userIds) {
            if (id == null) {
                continue;
            }
            if (selfId != null && selfId.equals(id)) {
                continue;
            }
            unique.add(id);
        }
        return new ArrayList<>(unique);
    }

    /** 用户必须存在且未被禁用。一次 IN 查询，不逐个查。 */
    private void assertUsersUsable(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        List<ImUser> users = userMapper.selectBatchIds(userIds);
        long usable = users.stream()
                .filter(u -> u.getStatus() != null && u.getStatus() == 1)
                .count();
        if (usable != userIds.size()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND,
                    "有 " + (userIds.size() - usable) + " 个用户不存在或已被禁用");
        }
    }
}

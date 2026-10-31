package com.linjian.im.chat.service;

import com.linjian.im.chat.dto.AdminSessionView;
import com.linjian.im.chat.dto.AdminStats;
import com.linjian.im.chat.dto.AdminUserView;
import com.linjian.im.chat.dto.MessagePage;
import com.linjian.im.chat.dto.MessageView;
import com.linjian.im.chat.dto.OnlineUserView;
import com.linjian.im.chat.dto.PageView;
import com.linjian.im.chat.entity.ImMessage;
import com.linjian.im.chat.entity.ImSession;
import com.linjian.im.chat.entity.ImUser;
import com.linjian.im.chat.mapper.ImMessageMapper;
import com.linjian.im.chat.mapper.ImSessionMapper;
import com.linjian.im.chat.mapper.ImUserMapper;
import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端服务：概览、用户管理、会话与消息审计、在线监控。
 *
 * <p><b>权限校验不在这里。</b>本类所有方法都假设调用者已是管理员——
 * 真正的拦截在 {@code SecurityConfig} 的 {@code /api/admin/** → hasRole("ADMIN")}。
 * 这不是「信任上游」，而是把同一件事只做一次：权限判断散落到每个方法里，
 * 迟早会漏掉一个，而且漏掉的时候不会有任何编译错误或测试失败提示。</p>
 *
 * <p>唯一在服务层保留的校验是「不能禁用自己 / 不能禁用管理员」，因为它不是权限问题，
 * 而是防止管理员把后台锁死——这类规则属于业务约束，放在这里才对。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_MESSAGE_LIMIT = 30;
    private static final int MAX_MESSAGE_LIMIT = 100;

    private final ImUserMapper userMapper;
    private final ImSessionMapper sessionMapper;
    private final ImMessageMapper messageMapper;
    private final PresenceService presenceService;
    private final AccountKicker accountKicker;

    /**
     * 概览指标。
     *
     * <p>六个数字来自三次 COUNT 和一次 SCAN。其中 {@code messageCount} 是全表扫描，
     * 随数据量线性变慢，其余都是索引上的常数级操作。全部串行执行，
     * 没有做「并行查再合并」——管理端首页不是高频路径，为它引入并行流
     * 只会让慢查询的排查变得更难。</p>
     */
    public AdminStats stats() {
        long users = userMapper.selectCount(null);
        long sessions = sessionMapper.countForAdmin(null);
        long groups = sessionMapper.countGroups();
        long messages = messageMapper.countAll();
        long today = messageMapper.countSince(LocalDate.now().atStartOfDay());
        long online = presenceService.listOnlineUserIds().size();
        return new AdminStats(users, groups, sessions, messages, today, online);
    }

    public PageView<AdminUserView> users(String keyword, int page, int size) {
        int safeSize = clampSize(size);
        int offset = offset(page, safeSize);
        long total = userMapper.countForAdmin(keyword);
        List<ImUser> rows = userMapper.selectPageForAdmin(keyword, offset, safeSize);
        return new PageView<>(rows.stream().map(AdminUserView::of).toList(), total);
    }

    /**
     * 启用 / 禁用账号。返回更新后的行，前端直接替换这一行而不必重查整页。
     *
     * <p>两条禁止规则：</p>
     * <ol>
     *   <li><b>不能禁用自己</b>——点错一次就把自己锁在门外，而恢复需要直接改库。</li>
     *   <li><b>不能禁用管理员</b>——两个管理员互相禁用，后台就没人能进了。
     *       真实系统会做「最后一个管理员保护」，这里直接禁止，规则更简单也更容易解释。</li>
     * </ol>
     *
     * <p><b>禁用之后的三条生效路径（缺一条这个功能就是半成品）：</b></p>
     * <ol>
     *   <li>REST 请求：{@code AccountStatusFilter} 每次请求查一次 status，立即失效。</li>
     *   <li>已建立的连接：{@link AccountKicker} 立刻断开，见下面的说明。</li>
     *   <li>后续重连：{@code AccountStatusChannelInterceptor} 在 CONNECT 阶段拒绝。</li>
     * </ol>
     *
     * <p>W9 最初只做了第 1、3 条，靠「下次发帧被拒」实现第 2 条的效果。问题在于
     * 那要等用户下一次操作：被禁用的人只要不断线，就能继续收到别人的消息，
     * 而管理员在界面上看到的是「已禁用」。踢线补的就是这一段——
     * 状态变更与连接断开发生在同一个方法里，不存在「忘了调用」的中间态。</p>
     */
    public AdminUserView updateStatus(long operatorId, Long targetId, int status) {
        if (status != 0 && status != 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "status 只能是 0 或 1");
        }
        if (targetId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "userId 不能为空");
        }
        if (operatorId == targetId) {
            throw new BusinessException(ErrorCode.CANNOT_DISABLE_SELF);
        }
        ImUser target = userMapper.selectById(targetId);
        if (target == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (target.getRole() != null && target.getRole() == 1) {
            throw new BusinessException(ErrorCode.ADMIN_OPERATION_REJECTED,
                    "不能修改管理员账号的状态");
        }
        userMapper.updateStatus(targetId, status);
        target.setStatus(status);
        log.info("管理员 {} 将账号 {} 的状态改为 {}", operatorId, targetId, status);

        // 只在禁用时踢线。恢复登录不需要断开——那人此刻本来就连不上，
        // 就算他还有一条侥幸活着的连接，也没有理由在「恢复」这个动作里把它掐掉。
        if (status == 0) {
            int closed = accountKicker.kick(targetId);
            if (closed > 0) {
                log.info("账号 {} 已被禁用，同时断开 {} 条在线连接", targetId, closed);
            }
        }
        return AdminUserView.of(target);
    }

    /**
     * 会话列表。成员数一次 GROUP BY 取齐，不逐条 count。
     */
    public PageView<AdminSessionView> sessions(String keyword, int page, int size) {
        int safeSize = clampSize(size);
        int offset = offset(page, safeSize);
        long total = sessionMapper.countForAdmin(keyword);
        List<ImSession> rows = sessionMapper.selectPageForAdmin(keyword, offset, safeSize);
        if (rows.isEmpty()) {
            return new PageView<>(List.of(), total);
        }

        Map<Long, Integer> memberCounts = new HashMap<>();
        for (ImSessionMapper.MemberCountRow row
                : sessionMapper.countMembers(rows.stream().map(ImSession::getId).toList())) {
            memberCounts.put(row.getSessionId(), row.getMemberCount());
        }

        List<AdminSessionView> views = new ArrayList<>(rows.size());
        for (ImSession s : rows) {
            views.add(new AdminSessionView(
                    s.getId(),
                    s.getSessionType(),
                    s.getName(),
                    s.getBizKey(),
                    memberCounts.getOrDefault(s.getId(), 0),
                    s.getLastMsgSeq(),
                    s.getLastMsgAt(),
                    s.getLastMsgPreview(),
                    s.getCreatedAt()));
        }
        return new PageView<>(views, total);
    }

    /**
     * 消息审计：按 sessionId 拉消息，<b>不做成员校验</b>。
     *
     * <p>这正是管理端存在的意义——否则它和普通接口没有区别。安全性由
     * {@code /api/admin/**} 的角色校验保证，而不是靠业务层的成员判断。</p>
     *
     * <p>分页语义与用户端一致：不传 beforeSeq 取最近一页；传了则向上翻。
     * 审计场景没有「增量补齐」的需求（没人会盯着一个历史会话等新消息），
     * 因此不提供 afterSeq。</p>
     */
    public MessagePage messages(Long sessionId, Long beforeSeq, Integer limit) {
        if (sessionId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "sessionId 不能为空");
        }
        if (sessionMapper.selectById(sessionId) == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        int size = limit == null
                ? DEFAULT_MESSAGE_LIMIT
                : Math.min(Math.max(limit, 1), MAX_MESSAGE_LIMIT);
        int probe = size + 1;

        List<ImMessage> raw;
        if (beforeSeq != null) {
            raw = new ArrayList<>(messageMapper.listBefore(sessionId, beforeSeq, probe));
        } else {
            raw = new ArrayList<>(messageMapper.listLatest(sessionId, probe));
        }
        Collections.reverse(raw);

        boolean hasMore = raw.size() > size;
        if (hasMore) {
            raw = raw.subList(0, size);
        }
        return new MessagePage(raw.stream().map(MessageView::of).toList(), hasMore);
    }

    /**
     * 在线用户列表。
     *
     * <p>先 SCAN 出在线用户 ID，再一次性 IN 查账号信息。连接数逐个查 Redis——
     * 在线人数是个位数到几十的量级，为它写一个 pipeline 反而增加代码面。
     * 如果哪天在线数上千，这里需要改成 pipeline 版；但那时更该做的是给监控页加分页。</p>
     *
     * <p>数据库中已不存在的用户（被删或测试残留）直接跳过，不在列表里留空行。</p>
     */
    public List<OnlineUserView> onlineUsers() {
        List<Long> ids = presenceService.listOnlineUserIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<ImUser> users = userMapper.selectBatchIds(ids);
        List<OnlineUserView> views = new ArrayList<>(users.size());
        for (ImUser u : users) {
            views.add(new OnlineUserView(u.getId(), u.getUsername(), u.getNickname(),
                    presenceService.onlineSessionCount(u.getId())));
        }
        // 连接多的排在前面：管理端看这个页面通常是想知道「谁还挂着连接」
        views.sort((a, b) -> Long.compare(b.connectionCount(), a.connectionCount()));
        return views;
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private static int offset(int page, int size) {
        return (Math.max(page, 1) - 1) * size;
    }
}

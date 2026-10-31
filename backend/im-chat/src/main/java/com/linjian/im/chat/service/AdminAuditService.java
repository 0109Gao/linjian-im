package com.linjian.im.chat.service;

import com.linjian.im.chat.dto.AdminLogView;
import com.linjian.im.chat.dto.PageView;
import com.linjian.im.chat.entity.ImAdminLog;
import com.linjian.im.chat.mapper.ImAdminLogMapper;
import com.linjian.im.common.id.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理员操作日志：写入与查询。
 *
 * <p>这个类解决的是「消息审计页审的是聊天内容，不是管理员动作」这个缺口。
 * 有了它，才能回答「谁在什么时候把谁的账号禁用了」，而不只是「这个会话里说过什么」。</p>
 *
 * <p><b>写日志失败不抛异常。</b>这一点是刻意的，也是唯一一处「审计可能丢」的妥协：
 * 审计失败时正确的做法既不是让业务失败（管理员点禁用没生效，却没人知道为什么），
 * 也不是静默吞掉（日志里什么都看不到）。折中是打一条 ERROR 并放行业务——
 * 这条 ERROR 的出现本身就是一个应当被处理的信号，而不是噪声。
 * 更严格的做法是先写日志再执行动作（audit-first），但那会让「动作失败」也留下一条
 * 看起来像成功的记录，对账时更麻烦。</p>
 *
 * <p>写入点在 {@code AdminController}，与业务动作紧挨着。没有用 AOP 注解：
 * 项目里没有引入 spring-boot-starter-aop，为四个动作引一个切面框架，
 * 收益不抵它带来的「注解加在哪、为什么这行没生效」这类新问题。
 * 代价是新增管理端接口时需要手动加一行——这一条写进了 {@code 技术方案.md} 的已知限制。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    /** 只读动作的去重窗口：同一管理员在同一会话上的连续查看，60 秒内只留一条。 */
    private static final int VIEW_DEDUP_SECONDS = 60;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ImAdminLogMapper logMapper;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 记一条操作日志。
     *
     * @param adminId    操作人
     * @param adminName  操作人登录名，调用方从 token 里取，避免再查一次库
     * @param action     见 {@link AdminActions}
     * @param targetType USER / SESSION / SYSTEM
     * @param targetId   目标 ID，SYSTEM 类为 null
     * @param detail     人可读说明
     * @param ip         来源 IP，取不到时可为 null
     */
    public void record(Long adminId, String adminName, String action,
                       String targetType, Long targetId, String detail, String ip) {
        ImAdminLog row = new ImAdminLog();
        row.setId(idGenerator.nextId());
        row.setAdminId(adminId);
        row.setAdminName(adminName == null ? "" : adminName);
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setDetail(truncate(detail));
        row.setIp(ip);
        try {
            logMapper.insert(row);
        } catch (RuntimeException e) {
            // 见类注释：不阻断业务，但必须留下 ERROR——这条日志本身就是待处理信号
            log.error("写入管理员操作日志失败 action={} adminId={} targetId={}", action, adminId, targetId, e);
        }
    }

    /**
     * 只读动作的写法：同一管理员、同一动作、同一目标在 60 秒内只记一次。
     *
     * <p>消息审计的接口是「打开抽屉一次、每次上拉加载更多一次」，不去重的话
     * 一个人翻十分钟就能把日志刷成几十行，把真正该被看到的禁用记录挤出第一页。</p>
     *
     * <p>先查后插而不是「插入时用 NOT EXISTS 子查询」：MySQL 允许在
     * {@code INSERT ... SELECT} 里引用目标表，但写出来的语句难读也难排查，
     * 而这里并发只有一个管理员，两次往返带来的竞态完全无所谓
     * （最坏结果是重复一行，不是丢失）。</p>
     *
     * @return 是否真的写入了一行，主要给测试断言用
     */
    public boolean recordView(Long adminId, String adminName, String action,
                              String targetType, Long targetId, String detail, String ip) {
        try {
            long recent = logMapper.countRecent(adminId, action, targetId, VIEW_DEDUP_SECONDS);
            if (recent > 0) {
                log.debug("跳过重复的查看日志 adminId={} action={} targetId={}", adminId, action, targetId);
                return false;
            }
        } catch (RuntimeException e) {
            log.warn("检查重复日志失败，继续写入：{}", e.getMessage());
        }
        record(adminId, adminName, action, targetType, targetId, detail, ip);
        return true;
    }

    /** 分页查询，供管理端「操作日志」页使用。 */
    public PageView<AdminLogView> query(String action, String keyword, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = (Math.max(page, 1) - 1) * safeSize;
        String safeAction = blankToNull(action);
        String safeKeyword = blankToNull(keyword);
        long total = logMapper.countForAdmin(safeAction, safeKeyword);
        List<ImAdminLog> rows = logMapper.selectPageForAdmin(safeAction, safeKeyword, offset, safeSize);
        return new PageView<>(rows.stream().map(AdminLogView::of).toList(), total);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** detail 列是 VARCHAR(512)。截断而不是让 MySQL 报错，日志不该因为一句话太长而丢整条。 */
    private static String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= 512 ? detail : detail.substring(0, 509) + "...";
    }
}

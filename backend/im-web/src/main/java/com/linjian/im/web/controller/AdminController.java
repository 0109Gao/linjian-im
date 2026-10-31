package com.linjian.im.web.controller;

import com.linjian.im.chat.dto.AdminLogView;
import com.linjian.im.chat.dto.AdminSessionView;
import com.linjian.im.chat.dto.AdminStats;
import com.linjian.im.chat.dto.AdminUserView;
import com.linjian.im.chat.dto.MessagePage;
import com.linjian.im.chat.dto.OnlineUserView;
import com.linjian.im.chat.dto.PageView;
import com.linjian.im.chat.dto.UpdateStatusRequest;
import com.linjian.im.chat.service.AdminActions;
import com.linjian.im.chat.service.AdminAuditService;
import com.linjian.im.chat.service.AdminService;
import com.linjian.im.chat.service.SeqService;
import com.linjian.im.common.api.Result;
import com.linjian.im.security.CurrentUser;
import com.linjian.im.web.audit.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端接口。整个前缀由 {@code SecurityConfig} 统一要求 role=1，
 * 因此这里的方法不再单独写权限判断——见 {@code AdminService} 的类注释。
 *
 * <p>为什么单独开 {@code /api/admin} 前缀而不是给现有接口加参数：
 * 前缀是一条能被一次性拦截的边界。混在用户端接口里靠参数区分，
 * 任何一次「忘了判断」都会让普通用户看到一个本该只有管理员能看的东西，
 * 而这种错误不会有编译错误，也不一定会被测试发现。</p>
 *
 * <p><b>操作日志写在这里，而不是写在 Service 里。</b>写日志需要两样东西只在这一层才有：
 * 请求 IP（{@link HttpServletRequest}）和 token 里的登录名。把它们作为参数一路传进
 * {@code AdminService}，会让业务方法多出两个与自己无关的参数，而且一旦有人从别处调用
 * 业务方法就会漏记。放在 Controller 的代价是<b>新增管理端接口时必须记得补一行</b>——
 * 这条已写进已知限制，是目前有意接受的取舍（没有为四个动作引入 AOP）。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final AdminAuditService auditService;
    private final SeqService seqService;

    /** 概览指标。 */
    @GetMapping("/stats")
    public Result<AdminStats> stats() {
        return Result.success(adminService.stats());
    }

    /** 用户列表，含已禁用账号（否则禁用后无法再启用）。 */
    @GetMapping("/users")
    public Result<PageView<AdminUserView>> users(@RequestParam(value = "keyword", required = false) String keyword,
                                                 @RequestParam(value = "page", defaultValue = "1") int page,
                                                 @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.success(adminService.users(keyword, page, size));
    }

    /**
     * 启用 / 禁用。status=0 禁用，status=1 启用。
     *
     * <p>用 PUT 而不是 PATCH：这是「把某人的状态设成 X」的幂等替换，重复调用结果相同，
     * 语义上本来就是 PUT。更实际的原因是 JDK 自带的 {@code HttpURLConnection}
     * 不支持 PATCH（会抛 {@code Invalid HTTP method: PATCH}），而 {@code TestRestTemplate}
     * 默认就用它——为了一处只改一个布尔字段的接口引入 Apache HttpClient 依赖并不划算。</p>
     *
     * <p>禁用时 {@code AdminService} 会顺带断开该用户的在线连接；这里只负责把这件事记下来。
     * 记录用「成功之后」而不是「之前」：一条「已禁用」的日志必须对应一次真的禁用，
     * 否则查账时无法区分「做过」和「点了但失败了」。</p>
     */
    @PutMapping("/users/{userId}/status")
    public Result<AdminUserView> updateStatus(@PathVariable Long userId,
                                              @Valid @RequestBody UpdateStatusRequest request,
                                              HttpServletRequest http) {
        long operatorId = CurrentUser.id();
        AdminUserView view = adminService.updateStatus(operatorId, userId, request.status());
        boolean disabling = request.status() == 0;
        auditService.record(operatorId, CurrentUser.username(),
                disabling ? AdminActions.USER_DISABLE : AdminActions.USER_ENABLE,
                AdminActions.TARGET_USER, userId,
                "账号 " + view.username() + " 状态改为" + (disabling ? "禁用" : "正常"),
                ClientIp.of(http));
        return Result.success(view);
    }

    /** 会话列表（全部会话，不按当前用户过滤）。 */
    @GetMapping("/sessions")
    public Result<PageView<AdminSessionView>> sessions(@RequestParam(value = "keyword", required = false) String keyword,
                                                       @RequestParam(value = "page", defaultValue = "1") int page,
                                                       @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.success(adminService.sessions(keyword, page, size));
    }

    /**
     * 消息审计：查看任意会话的消息。
     *
     * <p>路径故意做成 {@code /sessions/{id}/messages} 而不是 {@code /messages?sessionId=}，
     * 与用户端保持一致的形状，前端可以把两边的分页组件直接复用。</p>
     *
     * <p>这是整个系统里唯一「管理员能读到别人私聊」的接口，因此每次调用都要留痕。
     * 用带 60 秒去重的写法：翻页会连续调用（打开抽屉一次、上拉加载更多若干次），
     * 不去重的话日志会被自己刷屏，反而看不见禁用记录。</p>
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<MessagePage> messages(@PathVariable Long sessionId,
                                        @RequestParam(value = "beforeSeq", required = false) Long beforeSeq,
                                        @RequestParam(value = "limit", required = false) Integer limit,
                                        HttpServletRequest http) {
        MessagePage page = adminService.messages(sessionId, beforeSeq, limit);
        // 先取结果再记录：查询失败（会话不存在）时不该留下一条「查看过」的日志，
        // 那会让人以为敏感数据被读走了，而实际上什么都没读到。
        auditService.recordView(CurrentUser.id(), CurrentUser.username(),
                AdminActions.MSG_AUDIT_VIEW, AdminActions.TARGET_SESSION, sessionId,
                "查看会话消息 " + page.items().size() + " 条"
                        + (beforeSeq == null ? "" : "（beforeSeq=" + beforeSeq + "）"),
                ClientIp.of(http));
        return Result.success(page);
    }

    /** 在线用户，按连接数倒序。 */
    @GetMapping("/online")
    public Result<List<OnlineUserView>> online() {
        return Result.success(adminService.onlineUsers());
    }

    /**
     * 操作日志查询。筛选与分页都在库里做——日志会持续增长，一次性拉全量再前端分页，
     * 迟早会变成「点一下等三秒」。
     */
    @GetMapping("/logs")
    public Result<PageView<AdminLogView>> logs(@RequestParam(value = "action", required = false) String action,
                                               @RequestParam(value = "keyword", required = false) String keyword,
                                               @RequestParam(value = "page", defaultValue = "1") int page,
                                               @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.success(auditService.query(action, keyword, page, size));
    }

    /**
     * 重建会话序号计数（运维手段，不是日常操作）。
     *
     * <p>存在的意义：Redis 里的 {@code im:seq:*} 丢了之后，发消息会全量失败，
     * 而重启应用虽然会自动校准，但生产环境重启是有代价的（连接全断、正在进行的请求中断）。
     * 这个接口让同样的事可以不停机完成。</p>
     *
     * <p>用 POST 而不是 GET：它会写 Redis，属于有副作用的操作，不该被浏览器预取或爬虫触发。</p>
     */
    @PostMapping("/maintenance/seq/rebuild")
    public Result<SeqService.RebuildResult> rebuildSeq(HttpServletRequest http) {
        SeqService.RebuildResult result = seqService.rebuildAll();
        auditService.record(CurrentUser.id(), CurrentUser.username(),
                AdminActions.SEQ_REBUILD, AdminActions.TARGET_SYSTEM, null,
                "重建序号计数：扫描 " + result.sessions() + " 个会话，修正 "
                        + result.raised() + " 个，共推高 " + result.delta() + " 个号",
                ClientIp.of(http));
        return Result.success(result);
    }
}

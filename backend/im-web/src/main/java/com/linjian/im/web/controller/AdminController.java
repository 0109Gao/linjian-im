package com.linjian.im.web.controller;

import com.linjian.im.chat.dto.AdminSessionView;
import com.linjian.im.chat.dto.AdminStats;
import com.linjian.im.chat.dto.AdminUserView;
import com.linjian.im.chat.dto.MessagePage;
import com.linjian.im.chat.dto.OnlineUserView;
import com.linjian.im.chat.dto.PageView;
import com.linjian.im.chat.dto.UpdateStatusRequest;
import com.linjian.im.chat.service.AdminService;
import com.linjian.im.common.api.Result;
import com.linjian.im.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

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
     */
    @PutMapping("/users/{userId}/status")
    public Result<AdminUserView> updateStatus(@PathVariable Long userId,
                                              @Valid @RequestBody UpdateStatusRequest request) {
        return Result.success(adminService.updateStatus(CurrentUser.id(), userId, request.status()));
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
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<MessagePage> messages(@PathVariable Long sessionId,
                                        @RequestParam(value = "beforeSeq", required = false) Long beforeSeq,
                                        @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(adminService.messages(sessionId, beforeSeq, limit));
    }

    /** 在线用户，按连接数倒序。 */
    @GetMapping("/online")
    public Result<List<OnlineUserView>> online() {
        return Result.success(adminService.onlineUsers());
    }
}

package com.linjian.im.web.controller;

import com.linjian.im.chat.dto.AddMembersRequest;
import com.linjian.im.chat.dto.CreateGroupRequest;
import com.linjian.im.chat.dto.MemberView;
import com.linjian.im.chat.dto.MessagePage;
import com.linjian.im.chat.dto.OpenSessionRequest;
import com.linjian.im.chat.dto.PageView;
import com.linjian.im.chat.dto.ReadRequest;
import com.linjian.im.chat.dto.ReadResult;
import com.linjian.im.chat.dto.SessionView;
import com.linjian.im.chat.service.MessageService;
import com.linjian.im.chat.service.ReadService;
import com.linjian.im.chat.service.SessionService;
import com.linjian.im.common.api.Result;
import com.linjian.im.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话与消息的 REST 接口。
 *
 * <p>四类职责：会话的打开与创建、拉历史、报已读、群成员管理。
 * 实时收发全部走 STOMP，这些操作都是「补偿性」或「初始化」性质——
 * 它们必须能被重试，因此用 HTTP 状态码表达结果，而不是发出去就不管。</p>
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final MessageService messageService;
    private final ReadService readService;

    /** 单聊懒创建：不存在就建，已存在返回同一个 sessionId。 */
    @PostMapping("/open")
    public Result<Map<String, Object>> open(@Valid @RequestBody OpenSessionRequest request) {
        Long sessionId = sessionService.openSingle(CurrentUser.id(), request.peerUserId());
        return Result.success(Map.of("sessionId", sessionId));
    }

    /** 建群。创建者自动入群，返回新的 sessionId。 */
    @PostMapping("/group")
    public Result<Map<String, Object>> createGroup(@Valid @RequestBody CreateGroupRequest request) {
        Long sessionId = sessionService.createGroup(
                CurrentUser.id(), request.name(), request.memberIds());
        return Result.success(Map.of("sessionId", sessionId));
    }

    /**
     * 拉人入群。返回 {@code added}——真正新增的人数，不是提交的人数。
     *
     * <p>客户端拿这个数字决定提示「已邀请 3 人」还是「他们都在群里了」，
     * 比让前端自己比对成员列表可靠得多（那份列表随时会过期）。</p>
     */
    @PostMapping("/{sessionId}/members")
    public Result<Map<String, Object>> addMembers(@PathVariable Long sessionId,
                                                  @Valid @RequestBody AddMembersRequest request) {
        int added = sessionService.addMembers(CurrentUser.id(), sessionId, request.userIds());
        return Result.success(Map.of("sessionId", sessionId, "added", added));
    }

    /** 退群。返回剩余人数，0 表示这是最后一个离开的人。 */
    @DeleteMapping("/{sessionId}/members/me")
    public Result<Map<String, Object>> leave(@PathVariable Long sessionId) {
        int remaining = sessionService.leaveGroup(CurrentUser.id(), sessionId);
        return Result.success(Map.of("sessionId", sessionId, "remaining", remaining));
    }

    /** 群成员列表。非成员访问会被拒（4101），与拉消息同一套鉴权。 */
    @GetMapping("/{sessionId}/members")
    public Result<List<MemberView>> members(@PathVariable Long sessionId) {
        return Result.success(sessionService.members(CurrentUser.id(), sessionId));
    }

    @GetMapping
    public Result<PageView<SessionView>> list(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return Result.success(sessionService.list(CurrentUser.id(), page, size));
    }

    /**
     * 拉消息。三个参数互斥，都不传则返回最近一页。
     *
     * <p>{@code afterSeq} 用于断线重连补齐，{@code beforeSeq} 用于向上翻历史。
     * 两者都存在时优先 {@code afterSeq}，因为它对应「补齐」这种不能丢的场景。</p>
     */
    @GetMapping("/{sessionId}/messages")
    public Result<MessagePage> messages(@PathVariable Long sessionId,
                                        @RequestParam(required = false) Long afterSeq,
                                        @RequestParam(required = false) Long beforeSeq,
                                        @RequestParam(required = false) Integer limit) {
        return Result.success(messageService.list(CurrentUser.id(), sessionId, afterSeq, beforeSeq, limit));
    }

    @PostMapping("/{sessionId}/read")
    public Result<ReadResult> read(@PathVariable Long sessionId,
                                   @Valid @RequestBody ReadRequest request) {
        return Result.success(readService.markRead(CurrentUser.id(), sessionId, request.lastReadSeq()));
    }
}

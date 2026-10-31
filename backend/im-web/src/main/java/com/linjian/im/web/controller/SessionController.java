package com.linjian.im.web.controller;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话与消息的 REST 接口。
 *
 * <p>三类职责：打开会话、拉历史、报已读。实时收发全部走 STOMP，
 * 这三类都是「补偿性」或「初始化」操作——它们必须能被重试，因此用 HTTP 状态码表达结果。</p>
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final MessageService messageService;
    private final ReadService readService;

    /** 懒创建：不存在就建，已存在返回同一个 sessionId。 */
    @PostMapping("/open")
    public Result<Map<String, Object>> open(@Valid @RequestBody OpenSessionRequest request) {
        Long sessionId = sessionService.openSingle(CurrentUser.id(), request.peerUserId());
        return Result.success(Map.of("sessionId", sessionId));
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

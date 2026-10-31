package com.linjian.im.web.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * W6 的连通性探针。
 *
 * <p>为什么它必须要求鉴权：一个匿名的 ping 只能证明「连接建立成功」，
 * 而带了 Principal 的 ping 能同时证明三件事——握手闸放行了合法用户、
 * CONNECT 闸拒绝不了他、服务端能从这个会话里取出正确的身份。
 * 换句话说，这条 ping 本身就是一次活的鉴权验收。</p>
 *
 * <p>回执发往 {@code /user/queue/pong}：使用 user destination 而不是
 * {@code /topic/pong}，因为后者会把 A 的回显广播给全部连接，既泄露信息也无法验证身份绑定。</p>
 */
@Slf4j
@Controller
public class PingController {

    @MessageMapping("/ping")
    @SendToUser(value = "/queue/pong", broadcast = false)
    public PingResponse ping(@Payload(required = false) PingRequest request, Principal principal) {
        String userId = principal.getName();
        log.debug("收到 ping userId={}", userId);
        return new PingResponse(
                "pong",
                userId,
                request == null ? null : request.echo(),
                System.currentTimeMillis()
        );
    }

    public record PingRequest(String echo) {
    }

    public record PingResponse(String type, String userId, String echo, long serverTime) {
    }
}

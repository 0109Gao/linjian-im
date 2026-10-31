package com.linjian.im.web.ws;

import com.linjian.im.chat.dto.SendCommand;
import com.linjian.im.chat.dto.SendResult;
import com.linjian.im.chat.service.MessageService;
import com.linjian.im.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * 消息发送入口。
 *
 * <p>业务异常在这里被转成 {@link SendResult#fail}，而不是让它冒泡：Spring 的
 * {@code @MessageMapping} 里抛异常会回一个 ERROR 帧并<b>关闭整个 STOMP 会话</b>。
 * 一条消息内容不合法就断掉用户的连接，代价远高于收益——用户会看到整个人掉线，
 * 而不是「这条消息发不出去」。</p>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatController {

    private final MessageService messageService;

    @MessageMapping("/chat.send")
    @SendToUser(value = "/queue/ack", broadcast = false)
    public SendResult send(@Payload SendCommand command, Principal principal) {
        if (principal == null) {
            return SendResult.fail("未登录");
        }
        Long senderId = Long.valueOf(principal.getName());
        try {
            return messageService.send(senderId, command);
        } catch (BusinessException e) {
            log.debug("消息发送被拒绝 sender={} 原因={}", senderId, e.getMessage());
            return SendResult.fail(e.getMessage());
        } catch (RuntimeException e) {
            log.error("消息发送异常 sender={}", senderId, e);
            return SendResult.fail("服务内部错误");
        }
    }
}

package com.linjian.im.web.config;

import com.linjian.im.security.HandshakeAuthInterceptor;
import com.linjian.im.security.StompAuthChannelInterceptor;
import com.linjian.im.web.ws.AccountStatusChannelInterceptor;
import com.linjian.im.web.ws.GroupTopicAuthInterceptor;
import com.linjian.im.web.ws.WebSocketSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * STOMP 端点与消息代理配置。
 *
 * <p>五个关键点：</p>
 * <ol>
 *   <li>使用 SimpleBroker（内存代理）而非真实 MQ：单机 IM 的推送在进程内完成，
 *       引入 RabbitMQ 只会多一跳网络，收益要等到做多节点广播时才会出现。</li>
 *   <li>心跳 10 秒双向。心跳由服务端配置生效，客户端在 CONNECT 时确认接收。</li>
 *   <li>CONNECT 帧的鉴权挂在 inbound 通道最前端，先于任何业务处理。</li>
 *   <li>SUBSCRIBE 帧的群成员校验紧随其后。SimpleBroker 对 {@code /topic} 不做 ACL，
 *       没有这一步，任何登录用户猜到 sessionId 就能收走整个群的消息。</li>
 *   <li>每条连接建立时把 sessionId → session 登记到 {@code WebSocketSessionRegistry}，
 *       供「禁用时立刻踢下线」使用。这一条不参与消息收发，只在传输层挂一个装饰器。</li>
 * </ol>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final HandshakeAuthInterceptor handshakeAuthInterceptor;
    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final AccountStatusChannelInterceptor accountStatusChannelInterceptor;
    private final GroupTopicAuthInterceptor groupTopicAuthInterceptor;
    private final WebSocketSessionRegistry sessionRegistry;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 不走 SockJS：目标客户端是原生 WebSocket（@stomp/stompjs），
        // 而 SockJS 的降级路径在需要自定义鉴权头时反而成为障碍。
        registry.addEndpoint("/ws")
                .addInterceptors(handshakeAuthInterceptor)
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 客户端发往服务端的目的地前缀
        registry.setApplicationDestinationPrefixes("/app");
        // 服务端推送给单个用户的前缀，配合 Principal 使用
        registry.setUserDestinationPrefix("/user");
        // 广播与点对点队列
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(heartbeatScheduler());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 顺序有意义，三道闸必须严格按这个次序：
        //   1. 验签，把 Principal 绑到会话上（否则后面两道拿到的 user 是 null）
        //   2. 查账号状态，被禁用的人不该继续发帧
        //   3. 查群成员身份，非成员不该订阅该群主题
        // 任何一道提前，都会让下一道基于空身份做判断，表现为「所有人都被拒」。
        registration.interceptors(stompAuthChannelInterceptor,
                accountStatusChannelInterceptor,
                groupTopicAuthInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // 单帧上限 64KB、发送缓冲 512KB、10 秒发送超时。
        // W6 只跑 ping，这些值是为后续发消息预留的合理上界，不是压测调优结果。
        registration.setMessageSizeLimit(64 * 1024);
        registration.setSendBufferSizeLimit(512 * 1024);
        registration.setSendTimeLimit(10_000);
        // 会话登记：连接建立时记住句柄，关闭时忘掉。
        registration.addDecoratorFactory(sessionRecordingFactory());
    }

    /**
     * 把每条活连接登记下来，以便管理端能主动断开它。
     *
     * <p>挂在传输层的装饰器上，而不是在业务处理器里记——装饰器对所有连接都生效，
     * 包括那些 CONNECT 帧还没来、{@code SessionConnectedEvent} 还没触发的连接。
     * 挂在业务层的话，一个「握手成功但迟迟不发 CONNECT」的连接不会被登记，
     * 而它恰恰是最该被清理的那种。</p>
     *
     * <p>移除放在 {@code afterConnectionClosed}，并且不判空：
     * 断开事件必然对应一条登记（同一 session 只会建立一次），
     * 重复 remove 对 {@code ConcurrentHashMap} 是幂等的。</p>
     */
    @Bean
    public WebSocketHandlerDecoratorFactory sessionRecordingFactory() {
        return handler -> new WebSocketHandlerDecorator(handler) {

            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessionRegistry.add(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessionRegistry.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /**
     * 心跳调度器必须独立于业务线程池：心跳是定时任务，若与业务共用线程池，
     * 业务排队时心跳会跟着延迟，客户端会误判断线并触发无谓重连。
     */
    @Bean
    public ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }
}

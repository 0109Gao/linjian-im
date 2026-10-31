package com.linjian.im.web.ws;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP 目的地的唯一定义处。
 *
 * <p>推送方（{@link StompMessageDelivery}）和订阅校验方（{@link GroupTopicAuthInterceptor}）
 * 必须对「群主题长什么样」有一致的理解。把这个格式写成两个各自的正则，
 * 是最容易出现「推送发到 A、校验只看 B」的地方——而那种错配不会报错，
 * 只会表现为「群消息偶尔收不到」，极难定位。因此两边都用这里的方法。</p>
 */
public final class WsDestinations {

    /** 群聊广播目的地前缀。完整形态：{@code /topic/session.1234567890}。 */
    public static final String SESSION_TOPIC_PREFIX = "/topic/session.";

    /** 只匹配十进制数字，且长度限制在 long 的范围内（19 位）。 */
    private static final Pattern SESSION_TOPIC = Pattern.compile("^/topic/session\\.(\\d{1,19})$");

    private WsDestinations() {
    }

    public static String sessionTopic(Long sessionId) {
        return SESSION_TOPIC_PREFIX + sessionId;
    }

    /**
     * 从目的地解析会话 id。
     *
     * @return 会话 id；目的地不是群主题时返回 {@code null}（表示「不需要校验」）
     */
    public static Long parseSessionId(String destination) {
        if (destination == null) {
            return null;
        }
        Matcher matcher = SESSION_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            // 19 位数字也可能超过 Long.MAX_VALUE，这种目的地没有任何会话能命中，
            // 当作「不是群主题」放行即可——它收不到任何广播。
            return null;
        }
    }
}

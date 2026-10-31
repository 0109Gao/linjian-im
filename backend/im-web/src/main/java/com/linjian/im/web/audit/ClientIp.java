package com.linjian.im.web.audit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取请求来源 IP。
 *
 * <p>先看 {@code X-Forwarded-For} 再看 {@code RemoteAddr}，因为部署在 nginx 后面时
 * 后者恒为 127.0.0.1——审计日志里全是同一个地址，等于这个字段没写。</p>
 *
 * <p><b>这个头的值可以被客户端伪造。</b>审计场景下的正确做法是只信任「最后一跳代理追加的那一段」，
 * 但本项目没有网关、也没有固定的代理层数与可信名单，无法可靠切分。
 * 因此这里的取值只能作为参考线索：真正有法律效力的审计需要接入可信代理链或直接记录
 * 连接级地址。这一点写进了 {@code 技术方案.md} 的已知限制，而不是假装它准确。</p>
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 形如 "client, proxy1, proxy2"，第一段是原始客户端
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            String trimmed = first.trim();
            if (!trimmed.isEmpty()) {
                // 长度兜底：列是 VARCHAR(45)，异常长或畸形的头不应让整条日志写失败
                return trimmed.length() <= 45 ? trimmed : trimmed.substring(0, 45);
            }
        }
        String remote = request.getRemoteAddr();
        return remote != null && remote.length() <= 45 ? remote : null;
    }
}

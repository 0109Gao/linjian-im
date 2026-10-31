package com.linjian.im.chat.service;

import com.linjian.im.common.api.ErrorCode;
import com.linjian.im.common.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 消息正文校验。
 *
 * <p><b>长度按「字符」而不是「UTF-16 单元」计。</b>Java 的 {@code String.length()}
 * 返回的是 UTF-16 code unit 数，一个 emoji（如 😀）会被算成 2，
 * 一段由 emoji 构成的文本用 length() 量出来的数值是用户实际看到的 2 倍。
 * 如果拿 length() 去和上限比较，客户端显示「1999 字」的内容会被服务端拒收，
 * 而用户完全无法理解为什么。{@code codePointCount} 才是与「字数」对齐的口径。</p>
 *
 * <p>反过来的坑是数据库：{@code VARCHAR(2000)} 在 utf8mb4 下的上限是 2000 个字符，
 * 与 codePointCount 口径一致，因此 2000 个 emoji 可以正常入库，不会被截断。</p>
 */
@Component
public class ContentValidator {

    /** 单条消息的字符上限。2000 与常见 IM 产品一致，也远小于 64KB 的 STOMP 帧上限。 */
    public static final int MAX_CODE_POINTS = 2000;

    /**
     * 校验并返回可直接入库的正文。
     *
     * <p>纯空白判空用 {@code strip()}（能识别全角空格与 Unicode 空白），
     * 但返回值保留原文不 trim——用户打的空格是他的排版意图，不该被服务端擅自删掉。</p>
     */
    public String requireValid(String raw) {
        if (raw == null || raw.strip().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_CONTENT, "消息内容不能为空");
        }
        int codePoints = raw.codePointCount(0, raw.length());
        if (codePoints > MAX_CODE_POINTS) {
            throw new BusinessException(ErrorCode.INVALID_CONTENT,
                    "消息内容超过 " + MAX_CODE_POINTS + " 字符，当前 " + codePoints + " 字符");
        }
        return raw;
    }
}

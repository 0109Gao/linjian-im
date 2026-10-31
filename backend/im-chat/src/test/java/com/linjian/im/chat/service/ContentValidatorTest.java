package com.linjian.im.chat.service;

import com.linjian.im.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内容校验的边界。这里最值得测的不是「超长被拒」，而是「按字符还是按 UTF-16 单元计」——
 * 用错口径时，2000 个 emoji 会被算成 4000 而误拒，而用户看到的明明只有 2000 个字。
 */
class ContentValidatorTest {

    private final ContentValidator validator = new ContentValidator();

    private static final String EMOJI = "😀";

    @Test
    @DisplayName("null 被拒绝")
    void nullRejected() {
        assertThatThrownBy(() -> validator.requireValid(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("空串与纯空白被拒绝")
    void blankRejected() {
        assertThatThrownBy(() -> validator.requireValid("")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.requireValid("   ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.requireValid("\t\n")).isInstanceOf(BusinessException.class);
        // 全角空格：strip() 能识别，trim() 不能
        assertThatThrownBy(() -> validator.requireValid("\u3000\u3000")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("恰好 2000 个 ASCII 字符通过")
    void exactlyMaxAsciiPasses() {
        String text = "a".repeat(ContentValidator.MAX_CODE_POINTS);
        assertThat(validator.requireValid(text)).hasSize(2000);
    }

    @Test
    @DisplayName("2001 个 ASCII 字符被拒，且提示里带出实际字数")
    void overMaxAsciiRejected() {
        String text = "a".repeat(ContentValidator.MAX_CODE_POINTS + 1);
        assertThatThrownBy(() -> validator.requireValid(text))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2001");
    }

    @Test
    @DisplayName("2000 个 emoji 必须通过——这是按字符计数而非按 length() 计数的关键证据")
    void maxEmojiPasses() {
        String text = EMOJI.repeat(ContentValidator.MAX_CODE_POINTS);

        // 先证明这个字符串确实是「2000 个字符、4000 个 UTF-16 单元」：
        // 如果实现用 String.length() 做判断，下面这行断言就会失败。
        assertThat(text.length()).isEqualTo(4000);
        assertThat(text.codePointCount(0, text.length())).isEqualTo(2000);

        assertThat(validator.requireValid(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("2001 个 emoji 被拒")
    void overMaxEmojiRejected() {
        String text = EMOJI.repeat(ContentValidator.MAX_CODE_POINTS + 1);
        assertThatThrownBy(() -> validator.requireValid(text))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2001");
    }

    @Test
    @DisplayName("首尾空格被保留，不擅自 trim")
    void keepsSurroundingSpaces() {
        assertThat(validator.requireValid("  hello  ")).isEqualTo("  hello  ");
    }
}

package com.linjian.im.common.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * 只对「超出 JS 安全整数范围」的 Long 输出字符串。
 *
 * <p>背景：雪花 ID 量级约 1.1×10^17，超过 {@code Number.MAX_SAFE_INTEGER}（9007199254740991），
 * 浏览器 {@code JSON.parse} 会在末位静默舍入，且不报错。若一律把 Long 转字符串，
 * 分页的 {@code total} 也会变成字符串，前端做 {@code total > 0} 之类的比较会静默出错。</p>
 *
 * <p>因此这里按值域判断：安全范围内的仍输出数字，超出范围的才输出字符串。
 * 代价是同一个字段在不同数据量下类型可能不同——这个代价是可接受的，
 * 因为超范围的数值本来就不可能被 JS 精确表示。</p>
 */
public class LongSafeSerializer extends JsonSerializer<Long> {

    private static final long MAX_SAFE_INTEGER = 9007199254740991L;

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        if (value > MAX_SAFE_INTEGER || value < -MAX_SAFE_INTEGER) {
            gen.writeString(value.toString());
        } else {
            gen.writeNumber(value);
        }
    }
}

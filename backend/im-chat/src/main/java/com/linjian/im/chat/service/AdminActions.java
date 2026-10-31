package com.linjian.im.chat.service;

/**
 * 管理端动作常量。
 *
 * <p>集中在一处而不是散在调用点的字符串字面量：这些值会落库，改名就等于改数据格式。
 * 有一个地方可以对着看，比在三个 Controller 里搜 {@code "USER_DISABLE"} 靠得住。</p>
 *
 * <p>不定义成枚举：枚举落库要处理 {@code @EnumValue} 与未知值反序列化两件事，
 * 而这张表的价值恰恰是「十年后还读得懂」——字符串比枚举更不容易在重构中失配。</p>
 */
public final class AdminActions {

    private AdminActions() {
    }

    /** 禁用账号。 */
    public static final String USER_DISABLE = "USER_DISABLE";

    /** 恢复账号。 */
    public static final String USER_ENABLE = "USER_ENABLE";

    /** 查看某个会话的消息（消息审计）。高频只读，写入时按 60 秒去重。 */
    public static final String MSG_AUDIT_VIEW = "MSG_AUDIT_VIEW";

    /** 手动重建会话序号计数。 */
    public static final String SEQ_REBUILD = "SEQ_REBUILD";

    public static final String TARGET_USER = "USER";
    public static final String TARGET_SESSION = "SESSION";
    public static final String TARGET_SYSTEM = "SYSTEM";
}

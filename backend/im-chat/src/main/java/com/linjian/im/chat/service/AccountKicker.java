package com.linjian.im.chat.service;

/**
 * 「让这个人的在线连接立刻断开」的能力。
 *
 * <p>定义在 im-chat 而不是直接把实现放进来，是因为断线需要拿到 WebSocket 会话，
 * 而 WebSocket 容器配置在 im-web。im-chat 不能反过来依赖 im-web（依赖方向是
 * im-web → im-chat），所以这里只声明能力，由 im-web 提供实现。</p>
 *
 * <p>这样做的直接收益是：{@code AdminService#updateStatus} 里那一行
 * {@code accountKicker.kick(userId)} 与状态更新在同一个方法里。
 * 如果改成在 Controller 里补一句，那么「禁用」这个动作就有了两个必须同时做对的步骤，
 * 任何一次遗忘都会得到一个看起来正常、实际漏了半边的禁用功能——
 * 而这正是 W9 最初版本的形态。</p>
 */
public interface AccountKicker {

    /**
     * 断开指定用户的全部在线连接。
     *
     * <p>实现必须容忍「一条连接都没有」「连接已经断了」「连接正在写」这些情况，
     * 不抛异常：踢线失败不该让一次已经落库的禁用操作报错。</p>
     *
     * @return 实际关闭的连接数，仅供日志与测试断言
     */
    int kick(Long userId);
}

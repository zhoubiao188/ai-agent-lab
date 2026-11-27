package com.example.o11y.clock;

/**
 * 时间源抽象。
 *
 * <p>追踪代码里最怕的就是直接调 {@code System.currentTimeMillis()}:评测要跑 200 次故障注入,
 * 每次都有 400ms 超时和退避等待,真实时钟会让整个评测跑上一分钟以上,而且结果不可复现。
 * 把时间抽出来之后,单元测试用 {@link VirtualClock} 瞬间推进,Demo 用真实时钟。</p>
 */
public interface Clock {

    /** 单调递增的纳秒时钟,只用于算耗时,不受系统时间调整影响。 */
    long nanoTime();

    /** 墙上时间,只用于展示。 */
    long epochMilli();

    /** 让出时间。虚拟时钟下立即返回,真实时钟下真的睡。 */
    void sleep(long millis);
}

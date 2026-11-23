package com.example.memory.memory;

import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Msg;
import com.example.memory.llm.LlmProvider;
import com.example.memory.llm.dto.LlmRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同步调用 LLM 的小工具 —— 只给"摘要压缩"这种后台任务用。
 *
 * <p>为什么摘要必须<b>独立线程池 + 硬超时</b>:</p>
 * <ul>
 *   <li>用户正在等回复,摘要却卡在模型侧 60 秒 —— 用户会以为服务挂了;</li>
 *   <li>摘要和主对话共用一个池子,模型一慢,两边一起堵,故障放大;</li>
 *   <li>超时后必须能真正中断,所以用 {@code Future.get(timeout)} + {@code cancel(true)},
 *       而不是"等它自己结束"。</li>
 * </ul>
 */
@Component
public class LlmCaller {

    private static final Logger log = LoggerFactory.getLogger(LlmCaller.class);

    private final AgentProperties props;
    private final ExecutorService pool;

    public LlmCaller(AgentProperties props) {
        this.props = props;
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "memory-summarizer-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * 一次性拿到完整文本(不要流式)。失败/超时/空结果一律抛异常,由调用方决定降级策略。
     */
    public String complete(LlmProvider provider, String system, String user, double temperature, Duration timeout) {
        LlmRequest request = new LlmRequest(
                props.getLlm().getModel(),
                List.of(Msg.system(0, system), Msg.user(1, user)),
                List.of(),
                temperature);
        Future<String> future = pool.submit(() -> {
            StringBuilder sb = new StringBuilder();
            final Throwable[] error = new Throwable[1];
            provider.stream(request, new LlmProvider.StreamListener() {
                @Override
                public void onContentDelta(String delta) {
                    sb.append(delta);
                }

                @Override
                public void onToolCallDelta(int index, String id, String name, String argumentsFragment) {
                    // 摘要任务不需要工具;模型若强行发起调用,忽略即可
                }

                @Override
                public void onFinish(String finishReason, LlmProvider.TokenUsage usage) {
                }

                @Override
                public void onError(Throwable t) {
                    error[0] = t;
                }
            });
            if (error[0] != null) {
                throw new IllegalStateException(error[0].getMessage(), error[0]);
            }
            String text = sb.toString().trim();
            if (text.isEmpty()) {
                throw new IllegalStateException("摘要模型返回空内容");
            }
            return text;
        });

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("摘要调用超时(" + timeout.toSeconds() + "s)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("摘要调用失败: {}", cause.toString());
            throw new IllegalStateException(cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("摘要调用被中断", e);
        }
    }
}

package com.example.multiagent.orch;

import com.example.multiagent.exec.ExecutionContext;
import com.example.multiagent.exec.RunPolicy;
import com.example.multiagent.exec.ToolCall;
import com.example.multiagent.exec.ToolInvoker;
import com.example.multiagent.exec.ToolResult;
import com.example.multiagent.plan.Refs;
import com.example.multiagent.plan.Step;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 执行一个步骤:把 {@code $s1.paidAmount} 换成运行时值,然后交给工具层。
 *
 * <p>这里刻意<b>不</b>处理"数据流断了怎么办"的兜底:拿不到上游数据就必须失败,
 * 绝不能拿参数默认值硬跑。堵在这里的一次失败,会在后面变成一笔退错的款。</p>
 */
@Component
public class StepExecutor {

    private final ToolInvoker invoker;

    public StepExecutor(ToolInvoker invoker) {
        this.invoker = invoker;
    }

    public StepOutcome run(Step step, Map<String, JsonNode> outputs, ExecutionContext ctx, RunPolicy policy) {
        List<Refs.Ref> refs = step.refs();
        List<String> unresolved = new ArrayList<>();
        ObjectNode resolved = Refs.resolve(step.args(), ref -> {
            JsonNode source = outputs.get(ref.stepId());
            JsonNode value = source == null ? null : source.get(ref.field());
            if (value == null) {
                unresolved.add(ref.toString());
            }
            return value;
        });

        if (!unresolved.isEmpty()) {
            // 依赖断了:上游没跑,或字段不存在。这一步失败是正确行为
            return StepOutcome.failed(step.id(), step.tool(), 0,
                    "数据流断开,解析不到 " + unresolved, List.of());
        }

        ToolCall call = new ToolCall(step.id(), step.tool(), resolved, step.dependsOn());
        long t0 = System.currentTimeMillis();
        // 幂等键必须由"这次请求的内容"派生,不能只用任务 id:
        // 否则改对之后的第二次请求会被第一次的错误请求挡回去 —— 重规划白做,而且钱还是错的
        String idempotencyKey = ctx.idempotencyKey() + ":" + step.id() + ":"
                + Integer.toHexString(resolved.toString().hashCode());
        ToolResult result = invoker.invoke(call, ctx.withCall(step.id(), idempotencyKey), policy);
        long ms = System.currentTimeMillis() - t0;

        List<String> used = refs.stream().map(Refs.Ref::toString).toList();
        if (result.ok()) {
            return StepOutcome.ok(step.id(), step.tool(), ms, result.payload(), used);
        }
        return StepOutcome.failed(step.id(), step.tool(), ms,
                result.errorKind() + ": " + result.error(), used);
    }
}

package com.example.tool.agent;

import com.example.tool.exec.ToolCall;
import com.example.tool.exec.ToolResult;
import com.example.tool.spec.Violation;

import java.util.List;

/**
 * 规划器抽象。
 *
 * <p>默认实现是确定性的 {@link MockPlanner}:它按任务里的订单号生成固定的调用图,
 * 并按注入规则故意把参数写错。这样评测可复现,也不依赖任何 API Key。</p>
 *
 * <p>要接真实模型,只需实现这个接口 —— 编排器、并行执行、重试熔断、参数修复全都不用改。
 * 这也是把"模型"和"可靠性"分开的价值:换模型不该重写可靠性。</p>
 */
public interface Planner {

    List<ToolCall> plan(String task, String taskKey);

    /**
     * 参数校验失败后的"模型重想"一轮。
     *
     * @param violations 校验器报出来的具体错误,原文交给模型
     */
    ToolCall rewrite(ToolCall call, List<Violation> violations, String task);

    String summarize(String task, List<ToolResult> results);
}

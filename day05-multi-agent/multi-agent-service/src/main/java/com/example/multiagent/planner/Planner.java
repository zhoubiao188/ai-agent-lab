package com.example.multiagent.planner;

import com.example.multiagent.tasks.TaskSpec;

import com.example.multiagent.plan.Plan;

/**
 * 规划器接口。
 *
 * <p>换真实模型就换一个实现,其余的所有校验、覆盖核对、Critic、重规划都<b>不用改一行</b> ——
 * 因为它们只认 {@link Plan} 这个结构,不认是谁写的。</p>
 */
public interface Planner {

    Plan plan(TaskSpec task);
}

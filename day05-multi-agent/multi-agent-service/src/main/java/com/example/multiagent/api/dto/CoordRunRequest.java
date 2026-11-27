package com.example.multiagent.api.dto;

/** 一次手动运行的请求。{@code policy} 传配置名(前缀匹配),{@code flaw} 传缺陷类型或 null。 */
public record CoordRunRequest(String taskId, String policy, String flaw) {
}

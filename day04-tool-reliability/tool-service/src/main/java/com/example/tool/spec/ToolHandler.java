package com.example.tool.spec;

import com.example.tool.exec.ExecutionContext;
import com.example.tool.exec.ToolException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具实现。参数已经过 schema 校验与修复,实现里只需处理业务规则。
 */
@FunctionalInterface
public interface ToolHandler {

    JsonNode invoke(JsonNode args, ExecutionContext ctx) throws ToolException;
}

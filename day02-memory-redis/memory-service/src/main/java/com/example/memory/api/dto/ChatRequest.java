package com.example.memory.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话请求。
 * <p>{@code conversationId} 为空时由服务端新建 —— 前端"新建对话"就是不带这个字段。</p>
 */
public record ChatRequest(
        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "单条消息不能超过 4000 字")
        String message,

        String conversationId,

        String userId
) {
}

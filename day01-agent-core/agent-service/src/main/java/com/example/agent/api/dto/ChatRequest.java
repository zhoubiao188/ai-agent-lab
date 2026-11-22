package com.example.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 对话请求体。 */
public record ChatRequest(
        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "单条消息不要超过 4000 字")
        String message,

        /** 前端生成的会话 id;不传则服务端生成 */
        String conversationId,

        /** 便于多端区分,用于限流与埋点 */
        String userId
) {
}

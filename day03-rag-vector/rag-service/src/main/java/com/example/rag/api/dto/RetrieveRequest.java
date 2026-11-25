package com.example.rag.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 检索与问答的入参。mode 为空时用服务端默认(FULL)。 */
public record RetrieveRequest(@NotBlank String query, String mode, Integer topK) {
}

package com.example.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RunRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题过长")
        String question,
        /** NORMAL / SLOW_TOOL / RETRY_STORM / SILENT_FAILURE / COST_BLOWUP / ARG_DRIFT */
        String scenario,
        String userId) {
}

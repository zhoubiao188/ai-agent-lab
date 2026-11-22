package com.example.agent.tool.impl;

import com.example.agent.tool.AgentTool;
import com.example.agent.tool.Schemas;
import com.example.agent.tool.ToolContext;
import com.example.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 让模型拿到"真实现在几点"的最便宜的方式。
 * 模型自身的知识里没有当前时间,不调工具就一定会瞎编。
 */
@Component
public class CurrentTimeTool implements AgentTool {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE", Locale.SIMPLIFIED_CHINESE);

    @Override
    public String name() {
        return "get_current_time";
    }

    @Override
    public String description() {
        return "获取指定时区的当前日期与时间。当用户询问现在几点、今天日期、距某天还有几天等问题时必须调用。";
    }

    @Override
    public JsonNode parameterSchema() {
        return Schemas.string(Schemas.object(), "timezone",
                "IANA 时区名,例如 Asia/Shanghai、UTC、America/New_York。默认 Asia/Shanghai", false);
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String tz = Schemas.str(args, "timezone", "Asia/Shanghai");
        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (Exception e) {
            return ToolResult.fail("非法时区: " + tz + ",请使用 IANA 时区名", System.currentTimeMillis() - start);
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        String content = "当前时间(时区 %s): %s,ISO-8601: %s".formatted(
                zone, now.format(FMT), now.toOffsetDateTime());
        return ToolResult.ok(content, System.currentTimeMillis() - start);
    }
}

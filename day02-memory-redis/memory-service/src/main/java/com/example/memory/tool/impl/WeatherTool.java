package com.example.memory.tool.impl;

import com.example.memory.tool.AgentTool;
import com.example.memory.tool.Schemas;
import com.example.memory.tool.ToolContext;
import com.example.memory.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 天气查询(演示版,内置若干城市的模拟数据)。
 * <p>真实项目里把 {@link #query} 换成 WebClient 调高德/和风天气 API 即可,
 * 其余部分(超时、降级、错误信息回传给模型)的写法不用变。</p>
 */
@Component
public class WeatherTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    private static final Map<String, String[]> CITIES = new LinkedHashMap<>();

    static {
        CITIES.put("北京", new String[]{"晴", "18~27℃", "东北风 2 级", "空气质量良"});
        CITIES.put("上海", new String[]{"多云", "21~26℃", "东南风 3 级", "空气质量优"});
        CITIES.put("广州", new String[]{"雷阵雨", "25~31℃", "南风 3 级", "湿度 88%"});
        CITIES.put("深圳", new String[]{"阵雨", "26~30℃", "南风 2 级", "湿度 85%"});
        CITIES.put("杭州", new String[]{"阴", "19~25℃", "东风 2 级", "空气质量良"});
        CITIES.put("成都", new String[]{"小雨", "16~21℃", "北风 1 级", "湿度 90%"});
    }

    @Override
    public String name() {
        return "get_weather";
    }

    @Override
    public String description() {
        return "查询指定城市当前天气与温度。当用户询问天气、气温、是否下雨、出门是否带伞等问题时必须调用。";
    }

    @Override
    public JsonNode parameterSchema() {
        return Schemas.string(Schemas.object(), "city", "中文城市名,例如 北京、上海、杭州", true);
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String city = Schemas.str(args, "city", "").replace("市", "").trim();
        if (city.isEmpty()) {
            return ToolResult.fail("city 不能为空", System.currentTimeMillis() - start);
        }
        try {
            return ToolResult.ok(query(city), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("天气查询失败 city={}", city, e);
            return ToolResult.fail("天气服务暂时不可用: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private String query(String city) {
        String[] data = CITIES.get(city);
        if (data == null) {
            return "未收录城市「%s」的天气数据。当前仅支持:%s。请提示用户换一个城市。"
                    .formatted(city, String.join("、", CITIES.keySet()));
        }
        // 模拟网络抖动,避免演示时"假得太快"
        if (ThreadLocalRandom.current().nextInt(10) == 0) {
            throw new IllegalStateException("上游超时(模拟)");
        }
        return "%s 当前天气:%s,气温 %s,风力 %s,附加信息 %s。".formatted(city, data[0], data[1], data[2], data[3]);
    }
}

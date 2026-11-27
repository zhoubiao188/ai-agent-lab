package com.example.agent.api;

import com.example.agent.api.dto.MetaDtos;
import com.example.agent.fault.Scenario;
import com.example.agent.tool.ToolRegistry;
import com.example.o11y.decision.DecisionPoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 元信息接口。20 个决策点、6 个剧本、示例任务全部从代码里出。
 *
 * <p>为什么不让前端写死文案:决策点会加、剧本会加,写死的文案一次没同步,
 * 页面就会给你看一个"已经不存在的决策点",而这比没有页面更坏。</p>
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final ToolRegistry registry;

    public MetaController(ToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/catalog")
    public MetaDtos.CatalogDto catalog() {
        return new MetaDtos.CatalogDto(decisions(), scenarios(), tasks(), registry.size());
    }

    @GetMapping("/decisions")
    public List<MetaDtos.DecisionPointDto> decisions() {
        return Arrays.stream(DecisionPoint.values())
                .map(p -> new MetaDtos.DecisionPointDto(p.code(), p.title(), p.question(), p.symptom()))
                .toList();
    }

    @GetMapping("/scenarios")
    public List<MetaDtos.ScenarioDto> scenarios() {
        return Arrays.stream(Scenario.values())
                .map(s -> new MetaDtos.ScenarioDto(s.name(), s.label(), s.description(), s.culpritTool()))
                .toList();
    }

    @GetMapping("/tasks")
    public List<MetaDtos.TaskDto> tasks() {
        return List.of(
                new MetaDtos.TaskDto("t1", "帮我把订单 ORD-100234 的 299 元退款处理一下,不想要了",
                        "NORMAL", "标准退款:查订单 → 算可退 → 查政策 → 试算 → 写入"),
                new MetaDtos.TaskDto("t2", "订单 ORD-100235 还有库存吗?查一下 SKU-201",
                        "RETRY_STORM", "库存查询首次 503,重试一次才成功"),
                new MetaDtos.TaskDto("t3", "订单 ORD-100236 退款 128 元,质量问题",
                        "SILENT_FAILURE", "退款接口 200,但账务回显金额为 0,业务不校验"),
                new MetaDtos.TaskDto("t4", "退款政策是怎么规定的,多久能到账",
                        "COST_BLOWUP", "检索返回 40 条片段,自检告警后原样补查两轮"),
                new MetaDtos.TaskDto("t5", "订单 ORD-100237 要退款",
                        "ARG_DRIFT", "用户没给金额,绑定环节用默认值 0 元兜底"),
                new MetaDtos.TaskDto("t6", "订单 ORD-100236 的物流到哪了",
                        "SLOW_TOOL", "订单查询耗时超过单步预算,吃掉两次尝试"));
    }
}

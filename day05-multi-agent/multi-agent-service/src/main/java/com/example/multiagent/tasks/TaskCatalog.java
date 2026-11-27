package com.example.multiagent.tasks;

import com.example.multiagent.plan.PlanFlaw;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测任务集:20 个售后诉求,6 个计划正确、14 个带固定缺陷。
 *
 * <p>任务不是随手编的,每条都按"用户真的会怎么说"来写 —— 因为拆漏这件事的本质是
 * <b>用户的诉求本来就藏在自然语言里</b>。用户说"退款顺便让客服跟进一下",
 * 一半的计划会漏掉"跟进一下"。</p>
 */
@Component
public class TaskCatalog {

    private final Map<String, TaskSpec> tasks = new LinkedHashMap<>();

    public TaskCatalog() {
        add(TaskSpec.of("T01", "A10001 物流三天没更新,我要退款,顺便让客服跟进一下",
                "A10001", "SKU-1001", 1, "物流破损",
                List.of("order", "logistics", "quote", "refund", "ticket"), null));
        add(TaskSpec.of("T02", "A10002 收到的是坏的,退款,再帮我催一下物流,客服也要介入",
                "A10002", "SKU-1002", 1, "质量问题",
                List.of("order", "logistics", "quote", "refund", "ticket"), PlanFlaw.UNDER_COVER));
        add(TaskSpec.of("T03", "A10003 不想要了,能退多少退多少",
                "A10003", "SKU-1003", 1, "七天无理由",
                List.of("order", "quote", "refund"), PlanFlaw.WRONG_ORDER));
        add(TaskSpec.of("T04", "A10004 退货退款,退完告诉我到账时间",
                "A10004", "SKU-1004", 1, "七天无理由",
                List.of("order", "quote", "refund"), PlanFlaw.OVER_SPLIT));
        add(TaskSpec.of("T05", "A10005 东西没收到,申请退款",
                "A10005", "SKU-1005", 1, "七天无理由",
                List.of("order", "quote", "refund"), null));
        add(TaskSpec.of("T06", "A10006 包裹压坏了,退款 + 查物流 + 建个工单",
                "A10006", "SKU-1006", 1, "物流破损",
                List.of("order", "logistics", "quote", "refund", "ticket"), PlanFlaw.WRONG_ORDER));
        add(TaskSpec.of("T07", "A10007 尺寸不对,退款吧",
                "A10007", "SKU-1007", 1, "七天无理由",
                List.of("order", "quote", "refund"), PlanFlaw.UNDER_COVER));
        add(TaskSpec.of("T08", "A10008 我想换成同款,先看下还有没有货",
                "A10008", "SKU-1008", 2, "七天无理由",
                List.of("order", "inventory"), PlanFlaw.OVER_SPLIT));
        add(TaskSpec.of("T09", "A10009 物流停了两天,帮我查一下,不行就转人工",
                "A10009", "SKU-1009", 1, "七天无理由",
                List.of("order", "logistics", "ticket"), null));
        add(TaskSpec.of("T10", "A10010 一直显示运输中,查物流并且开个工单催",
                "A10010", "SKU-1010", 1, "七天无理由",
                List.of("order", "logistics", "ticket"), PlanFlaw.UNDER_COVER));
        add(TaskSpec.of("T11", "A10011 质量问题要退款,同时开个质量工单",
                "A10011", "SKU-1011", 1, "质量问题",
                List.of("order", "quote", "refund", "ticket"), PlanFlaw.WRONG_ORDER));
        add(TaskSpec.of("T12", "A10012 全部退掉,物流也查一下,客服跟进",
                "A10012", "SKU-1012", 1, "七天无理由",
                List.of("order", "logistics", "quote", "refund", "ticket"), PlanFlaw.OVER_SPLIT));
        add(TaskSpec.of("T13", "A10013 想再买两件,看看库存够不够",
                "A10013", "SKU-1013", 2, "七天无理由",
                List.of("order", "inventory"), null));
        add(TaskSpec.of("T14", "A10014 退款,物流也异常,一起处理下",
                "A10014", "SKU-1014", 1, "物流破损",
                List.of("order", "logistics", "quote", "refund", "ticket"), PlanFlaw.UNDER_COVER));
        add(TaskSpec.of("T15", "A10015 商品有瑕疵,退钱",
                "A10015", "SKU-1015", 1, "质量问题",
                List.of("order", "quote", "refund"), PlanFlaw.WRONG_ORDER));
        add(TaskSpec.of("T16", "A10016 我的快递到哪了",
                "A10016", "SKU-1016", 1, "七天无理由",
                List.of("order", "logistics"), PlanFlaw.OVER_SPLIT));
        add(TaskSpec.of("T17", "A10017 退款,并且让客服联系我",
                "A10017", "SKU-1017", 1, "七天无理由",
                List.of("order", "quote", "refund", "ticket"), null));
        add(TaskSpec.of("T18", "A10018 有货的话我就退款重买,没货就直接退",
                "A10018", "SKU-1018", 3, "七天无理由",
                List.of("order", "inventory", "quote", "refund"), PlanFlaw.UNDER_COVER));
        add(TaskSpec.of("T19", "A10019 物流太慢,退款,顺便查下到底卡在哪",
                "A10019", "SKU-1019", 1, "七天无理由",
                List.of("order", "logistics", "quote", "refund"), PlanFlaw.WRONG_ORDER));
        add(TaskSpec.of("T20", "A10020 全退了,物流和客服都跟一下",
                "A10020", "SKU-1020", 1, "七天无理由",
                List.of("order", "logistics", "quote", "refund", "ticket"), null));
    }

    private void add(TaskSpec spec) {
        tasks.put(spec.id(), spec);
    }

    public List<TaskSpec> all() {
        return List.copyOf(tasks.values());
    }

    public TaskSpec require(String id) {
        TaskSpec spec = tasks.get(id);
        if (spec == null) {
            throw new IllegalArgumentException("未知任务: " + id);
        }
        return spec;
    }

    public int size() {
        return tasks.size();
    }
}

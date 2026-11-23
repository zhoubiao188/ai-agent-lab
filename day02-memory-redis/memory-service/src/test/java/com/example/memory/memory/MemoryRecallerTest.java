package com.example.memory.memory;

import com.example.memory.domain.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRecallerTest {

    private final MemoryRecaller recaller = new MemoryRecaller();

    private List<Msg> archive() {
        List<Msg> msgs = new ArrayList<>();
        long seq = 1;
        // 轮 1:键盘故障
        msgs.add(Msg.user(seq++, "我上周买的机械键盘有几个按键失灵了"));
        msgs.add(Msg.assistant(seq++, "建议先做一次固件重置,如果无效可以走换货流程。", null));
        // 轮 2:退款时效
        msgs.add(Msg.user(seq++, "退款一般多久能到账?"));
        msgs.add(Msg.assistant(seq++, "退款原路退回,通常 1-3 个工作日到账,银行卡可能延后到 5 个工作日。", null));
        // 轮 3:发票
        msgs.add(Msg.user(seq++, "发票怎么开?需要提供什么资料"));
        msgs.add(Msg.assistant(seq++, "在订单详情页点击开票,填写抬头与税号即可,电子发票 24 小时内开出。", null));
        // 轮 4:会员
        msgs.add(Msg.user(seq++, "会员可以叠加优惠券吗"));
        msgs.add(Msg.assistant(seq++, "会员折扣与优惠券可以叠加,但同类券不可叠加使用。", null));
        return msgs;
    }

    @Test
    @DisplayName("按语义相关度找回被压缩的历史片段")
    void recallRelevantSegment() {
        List<MemoryRecaller.Hit> hits = recaller.recall("退款要多久才能到账", archive(), 3, 0.1);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).text()).contains("退款");
        assertThat(hits.get(0).text()).contains("工作日");
    }

    @Test
    @DisplayName("不相关的问题不会硬凑片段")
    void noIrrelevantHits() {
        List<MemoryRecaller.Hit> hits = recaller.recall("今天北京天气怎么样", archive(), 3, 0.3);
        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("英文与数字混排也能命中")
    void recallLatinTerms() {
        List<Msg> msgs = List.of(
                Msg.user(1, "Spring Boot 3 的 SSE 超时怎么配"),
                Msg.assistant(2, "设置 spring.mvc.async.request-timeout 即可。", null),
                Msg.user(3, "Redis 的 TTL 单位是什么"),
                Msg.assistant(4, "Redis TTL 以秒为单位,pexpire 用毫秒。", null));

        List<MemoryRecaller.Hit> hits = recaller.recall("Redis TTL 过期时间", msgs, 2, 0.1);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).text()).contains("Redis");
    }

    @Test
    @DisplayName("空归档/空查询直接返回空,不抛异常")
    void emptyInputs() {
        assertThat(recaller.recall("退款", List.of(), 3, 0.1)).isEmpty();
        assertThat(recaller.recall("", archive(), 3, 0.1)).isEmpty();
        assertThat(recaller.recall(null, archive(), 3, 0.1)).isEmpty();
    }

    @Test
    @DisplayName("中文二元组分词:短句整句保留,长句切二元组")
    void terms() {
        assertThat(MemoryRecaller.terms("退款")).containsExactly("退款");
        assertThat(MemoryRecaller.terms("退款到账")).contains("退款", "款到", "到账");
        assertThat(MemoryRecaller.terms("Redis TTL")).contains("redis", "ttl");
        assertThat(MemoryRecaller.terms("a b")).isEmpty();
    }

    @Test
    @DisplayName("渲染成 system 块时带序号,便于模型引用")
    void render() {
        List<MemoryRecaller.Hit> hits = recaller.recall("退款多久到账", archive(), 2, 0.1);
        String rendered = MemoryRecaller.render(hits);
        assertThat(rendered).contains("更早对话的相关片段");
        assertThat(rendered).contains("1. ");
    }
}

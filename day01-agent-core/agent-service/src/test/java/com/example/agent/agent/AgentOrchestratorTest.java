package com.example.agent.agent;

import com.example.agent.config.AgentProperties;
import com.example.agent.llm.MockLlmProvider;
import com.example.agent.llm.dto.Msg;
import com.example.agent.observe.AgentMetrics;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.tool.impl.CalculatorTool;
import com.example.agent.tool.impl.CurrentTimeTool;
import com.example.agent.tool.impl.KnowledgeBaseTool;
import com.example.agent.tool.impl.TicketTool;
import com.example.agent.tool.impl.WeatherTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排器的纯单元测试:不启动 Spring 容器,用假 sink 收集事件。
 * 这类测试比"起一个 @SpringBootTest 再调接口"快 10 倍以上,也更容易定位问题。
 */
class AgentOrchestratorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AgentOrchestrator orchestrator;
    private ConversationMemory memory;

    /** 事件收集器 */
    private static final class CollectingSink implements AgentEventSink {
        final List<String> events = new ArrayList<>();
        final List<Object> payloads = new ArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean(false);
        final StringBuilder text = new StringBuilder();

        @Override
        public void emit(String event, Object data) {
            events.add(event);
            payloads.add(data);
            if ("delta".equals(event) && data instanceof Map<?, ?> m) {
                text.append(m.get("text"));
            } else if ("delta_reset".equals(event)) {
                text.setLength(0);
            }
        }

        @Override
        public boolean closed() {
            return closed.get();
        }

        long count(String event) {
            return events.stream().filter(event::equals).count();
        }
    }

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties();
        props.setProvider("mock");
        props.setMaxSteps(4);
        memory = new ConversationMemory(props);
        ToolRegistry registry = new ToolRegistry(List.of(
                new CalculatorTool(), new CurrentTimeTool(), new KnowledgeBaseTool(),
                new TicketTool(), new WeatherTool()));
        AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());
        orchestrator = new AgentOrchestrator(new MockLlmProvider(mapper), registry, memory, props, metrics, mapper);
    }

    @Test
    void singleToolCallProducesStreamedAnswer() {
        CollectingSink sink = new CollectingSink();
        orchestrator.run("c1", "u1", "北京天气怎么样", sink);

        assertEquals(1, sink.count("tool_start"));
        assertEquals(1, sink.count("tool_result"));
        assertEquals(1, sink.count("message_end"));
        assertTrue(sink.text.toString().contains("晴"), sink.text.toString());
        assertEquals(List.of("user", "assistant", "tool", "assistant"), roles("c1"));
    }

    @Test
    void multipleToolsInOneStepAreAllExecuted() {
        CollectingSink sink = new CollectingSink();
        orchestrator.run("c2", "u1", "北京天气怎么样,再帮我算 (2+3)*4", sink);

        assertEquals(2, sink.count("tool_start"));
        assertEquals(2, sink.count("tool_result"));
        String text = sink.text.toString();
        assertTrue(text.contains("晴"), text);
        assertTrue(text.contains("= 20"), text);
    }

    @Test
    void knowledgeBaseAnswerIsGroundedInToolOutput() {
        CollectingSink sink = new CollectingSink();
        orchestrator.run("c3", "u1", "退货的运费谁出?", sink);
        assertTrue(sink.text.toString().contains("KB-001"), sink.text.toString());
    }

    @Test
    void ticketToolIsIdempotentWithinConversation() {
        orchestrator.run("c4", "u1", "我要转人工投诉", new CollectingSink());
        CollectingSink second = new CollectingSink();
        orchestrator.run("c4", "u1", "我要转人工投诉", second);

        long okCount = second.payloads.stream()
                .filter(p -> p instanceof Map<?, ?> m && "create_support_ticket".equals(m.get("name")))
                .filter(p -> Boolean.TRUE.equals(((Map<?, ?>) p).get("success")))
                .count();
        assertEquals(1, okCount);
        assertTrue(second.payloads.stream().anyMatch(p -> p instanceof Map<?, ?> m
                && String.valueOf(m.get("content")).contains("幂等命中")));
    }

    @Test
    void memoryNeverStartsWithOrphanToolMessage() {
        AgentProperties props = new AgentProperties();
        props.setMemoryWindow(4);
        ConversationMemory small = new ConversationMemory(props);
        small.append("cx", Msg.user("a"));
        small.append("cx", Msg.assistant(null, List.of(
                new com.example.agent.llm.dto.ToolCall("call1", "get_weather", "{\"city\":\"北京\"}"))));
        small.append("cx", Msg.tool("call1", "get_weather", "晴"));
        small.append("cx", Msg.assistant("答案是晴", null));
        small.append("cx", Msg.user("b"));

        List<Msg> history = small.history("cx");
        assertFalse(history.isEmpty());
        assertFalse("tool".equals(history.get(0).role()), history.toString());
    }

    private List<String> roles(String cid) {
        return memory.history(cid).stream().map(Msg::role).toList();
    }
}

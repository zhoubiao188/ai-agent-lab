package com.example.agent;

import com.example.agent.agent.ConversationMemory;
import com.example.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
@EnableScheduling
public class AgentServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceApplication.class);

    private final ConversationMemory memory;

    public AgentServiceApplication(ConversationMemory memory) {
        this.memory = memory;
    }

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }

    /** 每 10 分钟清理一次空闲会话,避免长跑进程内存缓慢上涨 */
    @Scheduled(fixedDelay = 600_000L)
    public void evictIdleSessions() {
        int removed = memory.evictIdle();
        if (removed > 0) {
            log.info("清理空闲会话 {} 个,当前存活 {}", removed, memory.sessionCount());
        }
    }
}

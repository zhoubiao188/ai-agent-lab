package com.example.memory;

import com.example.memory.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 * <p>{@code @EnableScheduling} 给内存存储的过期清理用(Redis 侧靠 TTL,不需要)。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
@EnableScheduling
public class MemoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryServiceApplication.class, args);
    }
}

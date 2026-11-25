package com.example.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * RAG 检索服务入口。
 * <p>启动时会做一次离线索引构建:读 kb 目录 → 分块 → 生成向量 → 建 BM25 倒排 + 向量索引。
 * 真实项目里这一步应该是离线的 ETL 任务,这里为了 demo 开箱可跑放在启动期。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RagServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagServiceApplication.class, args);
    }
}

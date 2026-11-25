package com.example.rag.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 从 classpath:kb/*.md 读知识库文档。生产环境换成对象存储或数据库,接口不变。 */
@Component
public class DocLoader {

    private static final Logger log = LoggerFactory.getLogger(DocLoader.class);

    public record Doc(String id, String title, String body) {
    }

    public List<Doc> load(String pattern) {
        List<Doc> docs = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            // 排序保证每次构建索引的顺序一致,否则 chunk id 会漂移,评测结果无法对比
            Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (Resource res : resources) {
                if (!res.exists()) {
                    continue;
                }
                String body = read(res);
                String file = res.getFilename() == null ? "unknown" : res.getFilename();
                String id = file.endsWith(".md") ? file.substring(0, file.length() - 3) : file;
                String title = firstHeading(body).orElse(id);
                docs.add(new Doc(id, title, body));
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载知识库失败: " + pattern, e);
        }
        log.info("知识库加载完成: {} 篇文档", docs.size());
        return docs;
    }

    private java.util.Optional<String> firstHeading(String body) {
        for (String line : body.split("\n")) {
            if (line.startsWith("# ")) {
                return java.util.Optional.of(line.substring(2).trim());
            }
        }
        return java.util.Optional.empty();
    }

    private String read(Resource res) throws IOException {
        try (InputStream in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

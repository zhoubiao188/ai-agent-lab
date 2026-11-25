package com.example.rag.api;

import com.example.rag.api.dto.RetrieveRequest;
import com.example.rag.api.dto.RetrieveResponse;
import com.example.rag.search.RetrievalMode;
import com.example.rag.search.RetrievalService;
import com.example.rag.search.ScoredChunk;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 检索调试入口。四种模式可以并排对比,这是调 RAG 时用得最多的接口。 */
@RestController
@RequestMapping("/api")
public class RetrievalController {

    private final RetrievalService retrieval;

    public RetrievalController(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    @PostMapping("/retrieve")
    public RetrieveResponse retrieve(@Valid @RequestBody RetrieveRequest req) {
        RetrievalMode mode = RetrievalMode.from(req.mode());
        int topK = req.topK() == null ? 8 : req.topK();
        long t0 = System.currentTimeMillis();
        List<ScoredChunk> hits = retrieval.retrieve(req.query(), mode, topK);
        return new RetrieveResponse(req.query(), mode.name(), System.currentTimeMillis() - t0, hits.size(), hits);
    }
}

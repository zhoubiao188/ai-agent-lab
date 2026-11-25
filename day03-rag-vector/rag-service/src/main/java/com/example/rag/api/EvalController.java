package com.example.rag.api;

import com.example.rag.eval.EvalCase;
import com.example.rag.eval.EvalReport;
import com.example.rag.eval.EvalRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 评测接口。force=true 会强制重跑,默认返回缓存结果。 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalRunner runner;

    public EvalController(EvalRunner runner) {
        this.runner = runner;
    }

    @GetMapping("/report")
    public EvalReport report(@RequestParam(defaultValue = "false") boolean force) {
        return runner.report(force);
    }

    @GetMapping("/cases")
    public List<EvalCase> cases() {
        return runner.loadCases();
    }
}

package com.example.multiagent.coverage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 覆盖核对结果:哪些诉求被计划覆盖了,哪些完全没被提到。 */
public record CoverageReport(List<String> required, Set<String> covered, Set<String> missing) {

    public CoverageReport {
        required = List.copyOf(required);
        covered = new LinkedHashSet<>(covered);
        missing = new LinkedHashSet<>(missing);
    }

    public boolean complete() {
        return missing.isEmpty();
    }

    public double ratio() {
        return required.isEmpty() ? 1.0 : (double) covered.size() / required.size();
    }
}

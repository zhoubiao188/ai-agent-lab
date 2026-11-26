package com.example.tool.repair;

import com.example.tool.spec.Violation;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 修复结果。{@code remaining} 为空表示可以直接执行。
 */
public record RepairOutcome(ObjectNode args, List<Repair> applied, List<Violation> remaining) {

    public boolean clean() {
        return remaining.stream().noneMatch(Violation::blocking);
    }

    public boolean changed() {
        return !applied.isEmpty();
    }
}

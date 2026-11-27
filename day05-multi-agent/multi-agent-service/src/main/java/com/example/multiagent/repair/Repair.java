package com.example.multiagent.repair;

/**
 * 一次参数修复的记录。留痕是为了两件事:线上排障,以及事后统计哪条规则最常被触发。
 */
public record Repair(String path, String rule, String from, String to) {

    @Override
    public String toString() {
        return path + " " + rule + ": " + from + " -> " + to;
    }
}

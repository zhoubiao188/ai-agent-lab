package com.example.agent.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位集合,额外记住每个值的来源。
 *
 * <p>来源这件事在业务代码里经常被丢掉:绑定之后只剩一个 {@code amount=0},
 * 谁也不知道这个 0 是用户说的、记忆里的,还是兜底默认的。丢掉来源,
 * 就等于丢掉"这次可能错在哪"的唯一线索。</p>
 */
public final class Slots {

    public static final String TEXT = "TEXT";
    public static final String MEMORY = "MEMORY";
    public static final String DEFAULT = "DEFAULT";

    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();

    public void put(String name, Object value, String source) {
        values.put(name, value);
        sources.put(name, source);
    }

    public Object get(String name) {
        return values.get(name);
    }

    public String string(String name) {
        Object v = values.get(name);
        return v == null ? null : String.valueOf(v);
    }

    public Integer integer(String name) {
        Object v = values.get(name);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String source(String name) {
        return sources.getOrDefault(name, DEFAULT);
    }

    public boolean has(String name) {
        return values.containsKey(name) && values.get(name) != null
                && !String.valueOf(values.get(name)).isBlank();
    }

    public Map<String, Object> values() {
        return new LinkedHashMap<>(values);
    }

    public Map<String, String> sources() {
        return new LinkedHashMap<>(sources);
    }

    /** 用默认值兜底的槽位名。它们就是"看起来成功"的高危集合。 */
    public List<String> defaulted() {
        List<String> out = new ArrayList<>();
        sources.forEach((k, v) -> {
            if (DEFAULT.equals(v)) {
                out.add(k);
            }
        });
        return out;
    }

    public long filledByText() {
        return sources.values().stream().filter(TEXT::equals).count();
    }
}

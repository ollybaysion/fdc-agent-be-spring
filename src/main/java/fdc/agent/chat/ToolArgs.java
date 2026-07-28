package fdc.agent.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 툴 인자 읽기 — LLM 이 준 값은 느슨하게 수용하고, 빈 값은 없는 것으로 본다. */
final class ToolArgs {
    private ToolArgs() {
    }

    /** null·공백이면 null, 그 외 trim 한 문자열. */
    static String text(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object raw = args.get(key);
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }

    /** 문자열 배열 인자 — 비어 있거나 배열이 아니면 null. */
    static List<String> strings(Map<String, Object> args, String key) {
        if (args == null || !(args.get(key) instanceof List<?> raw)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (o == null) {
                continue;
            }
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out.isEmpty() ? null : out;
    }

    // 아래 조립기는 전부 LinkedHashMap 이다 — 툴 스펙은 프롬프트의 일부라
    // 직렬화 순서가 실행마다 흔들리면 안 된다(Map.of 는 순서를 보장하지 않는다).

    /** {@code {type:object, properties:…, required:…}} JSON Schema 조립. */
    static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    /** 문자열 프로퍼티 한 칸. */
    static Map<String, Object> string(String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string");
        prop.put("description", description);
        return prop;
    }

    /** 문자열 배열 프로퍼티 한 칸. */
    static Map<String, Object> stringArray(String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "array");
        prop.put("items", Map.of("type", "string"));
        prop.put("description", description);
        return prop;
    }
}

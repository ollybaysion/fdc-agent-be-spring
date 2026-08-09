package fdc.agent.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 툴 인자 읽기 — LLM 이 준 값은 느슨하게 수용하고, 빈 값은 없는 것으로 본다. */
final class ToolArgs {
    private ToolArgs() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

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

    /** 느슨한 boolean 인자 — {@code Boolean} 이거나 "true"(대소문자 무관)면 true. */
    static boolean flag(Map<String, Object> args, String key) {
        if (args == null) {
            return false;
        }
        Object raw = args.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw != null && "true".equalsIgnoreCase(String.valueOf(raw));
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

    /**
     * 객체 인자({@code {"snsr_id":"S-0004"}}) — 값은 전부 문자열로 접고 빈 값은 버린다.
     * <b>JSON 문자열로 온 것도 받는다</b>: 중첩 객체를 스키마대로 못 내는 모델이 통째로
     * 따옴표에 싸서 보내는 일이 흔한데, 그걸 형식 오류로 되돌리면 왕복만 한 번 더 돈다.
     */
    static Map<String, String> map(Map<String, Object> args, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        if (args == null) {
            return out;
        }
        Object raw = args.get(key);
        if (raw instanceof String s) {
            String text = s.trim();
            if (!text.startsWith("{")) {
                return out;
            }
            try {
                raw = JSON.readValue(text, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return out;
            }
        }
        if (!(raw instanceof Map<?, ?> m)) {
            return out;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String value = String.valueOf(e.getValue()).trim();
            if (!value.isEmpty()) {
                out.put(String.valueOf(e.getKey()).trim(), value);
            }
        }
        return out;
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

    /**
     * 닫힌 목록 문자열 프로퍼티 — 목록 밖 값은 스키마 단계에서 못 만든다. 검증으로
     * 되돌려 보내는 것보다 애초에 만들 수 없게 하는 쪽이 왕복을 아낀다.
     */
    static Map<String, Object> stringEnum(String description, List<String> values) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string");
        prop.put("enum", values);
        prop.put("description", description);
        return prop;
    }

    /** 키가 자유로운 객체 프로퍼티 한 칸(값은 전부 문자열). */
    static Map<String, Object> stringMap(String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "object");
        prop.put("additionalProperties", Map.of("type", "string"));
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

    /** boolean 프로퍼티 한 칸. */
    static Map<String, Object> bool(String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "boolean");
        prop.put("description", description);
        return prop;
    }

    /** 객체 배열 프로퍼티 한 칸 — items 는 properties/required 로 조립한 하위 스키마. */
    static Map<String, Object> objectArray(
            String description, Map<String, Object> itemProperties, List<String> itemRequired) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "array");
        prop.put("items", schema(itemProperties, itemRequired));
        prop.put("description", description);
        return prop;
    }
}

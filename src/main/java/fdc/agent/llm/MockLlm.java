package fdc.agent.llm;

import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmToolSpec;
import fdc.agent.llm.LlmTypes.LlmTurn;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 결정적 mock LLM (LLM_BASE_URL 미설정 시, Node 판 llm/mock.ts 대응). 온프렘
 * LLM 없이도 에이전트 루프·툴 호출·SSE 를 end-to-end 로 검증하기 위한 것.
 * 키워드로 툴 호출을 판단하고, 툴 결과가 오면 그 요약을 최종 답으로 돌려준다.
 */
public class MockLlm implements LlmClient {

    // 설비 ID 패턴 (예: ETCH-01, CVD-02). 대문자 2~4 + "-" + 숫자 2+.
    private static final Pattern ID_RE = Pattern.compile("\\b([A-Z]{2,4}-\\d{2,})\\b");
    // 센서 ID 패턴 (예: S-0004). 도메인 스킬(snsr_id 파라미터) 호출용.
    private static final Pattern SENSOR_RE = Pattern.compile("\\bS-\\d{3,}\\b");

    @Override
    public LlmTurn next(List<LlmMessage> messages, List<LlmToolSpec> tools) {
        LlmMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (last != null && "tool".equals(last.role())) {
            return new LlmTurn.Final(last.content() != null ? last.content() : "");
        }
        String userText = lastUserText(messages);
        LlmToolCall call = planCall(userText, tools);
        if (call != null) {
            return new LlmTurn.ToolCalls(List.of(call));
        }
        return new LlmTurn.Final(genericAnswer(userText));
    }

    private static String lastUserText(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage m = messages.get(i);
            if ("user".equals(m.role())) {
                return m.content() != null ? m.content() : "";
            }
        }
        return "";
    }

    /** 키워드 기반 툴 선택. 설비 ID 가 없으면 툴 호출 안 함(일반 안내). */
    private static LlmToolCall planCall(String text, List<LlmToolSpec> tools) {
        // 센서 ID → snsr_id 파라미터를 요구하는 도메인 스킬 툴로(툴 이름 무관하게
        // 파라미터로 매칭 — 스킬이 늘어도 mock 무수정).
        Matcher sensorMatch = SENSOR_RE.matcher(text);
        if (sensorMatch.find()) {
            LlmToolSpec skill = tools.stream()
                    .filter(t -> requiresParam(t, "snsr_id"))
                    .findFirst()
                    .orElse(null);
            if (skill != null) {
                return new LlmToolCall("call_1", skill.name(), Map.of("snsr_id", sensorMatch.group()));
            }
        }

        Matcher idMatch = ID_RE.matcher(text);
        if (!idMatch.find()) {
            return null;
        }
        String id = idMatch.group(1);

        if (matches(text, "동종|피어|비슷|같은\\s*모델|peer") && has(tools, "get_peers")) {
            return new LlmToolCall("call_1", "get_peers", Map.of("id", id));
        }
        if (matches(text, "셋업|set\\s*up|정비|이벤트|이력|maintenance") && has(tools, "get_setup_events")) {
            return new LlmToolCall("call_1", "get_setup_events", Map.of("id", id));
        }
        if (has(tools, "get_equipment_detail")) {
            return new LlmToolCall("call_1", "get_equipment_detail", Map.of("id", id));
        }
        return null;
    }

    private static boolean matches(String text, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static boolean has(List<LlmToolSpec> tools, String name) {
        return tools.stream().anyMatch(t -> t.name().equals(name));
    }

    /** 툴의 JSON Schema parameters.required 에 param 이 있는지. */
    private static boolean requiresParam(LlmToolSpec tool, String param) {
        return tool.parameters().get("required") instanceof List<?> required
                && required.contains(param);
    }

    private static String genericAnswer(String text) {
        String q = text.trim();
        return (q.isEmpty() ? "" : "'" + q + "' 질문 주셨네요. ")
                + "설비 ID(예: ETCH-01)를 알려주시면 설비·챔버·센서 상세를 조회해 드립니다. "
                + "(fdc-agent-be Phase 2 — 온프렘 LLM 미설정 시 mock 응답)";
    }
}

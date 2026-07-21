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
    private static final String REQUEST_DATA_TOOL = "request_data";

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
        if (idMatch.find()) {
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

        // 설비/센서 ID 가 없는데 특정 데이터를 요구하면 — DB 없이 조달을 요청(request_data).
        // 실 LLM 이라면 모델이 "무엇이 없는지" 판단할 자리를, mock 은 키워드로 흉내낸다.
        // 주입된 [분석 대상]/[제공된 데이터] 블록은 빼고 원 질문만 본다 — 붙여넣은 라벨이
        // 오발화하지 않게(억제는 에이전트가 queryKey 로 확정한다).
        DataNeed need = matchDataNeed(beforeInjectedBlocks(text));
        if (need != null && has(tools, REQUEST_DATA_TOOL)) {
            return new LlmToolCall("call_1", REQUEST_DATA_TOOL, Map.of(
                    "queryKey", need.queryKey(),
                    "label", need.label(),
                    "sql", need.sql(),
                    "columns", need.columns()));
        }
        return null;
    }

    /** 주입된 [분석 대상]/[제공된 데이터] 블록을 뗀 원 질문(키워드 오발화 방지). */
    private static String beforeInjectedBlocks(String text) {
        int idx = text.indexOf("\n\n[");
        return idx >= 0 ? text.substring(0, idx) : text;
    }

    private record DataNeed(
            String queryKey, String label, String sql, List<String> columns, List<String> triggers) {
    }

    // DB 없이 조달을 요청할 만한 데이터(FE mock 의 REQUESTABLE 대응).
    private static final List<DataNeed> REQUESTABLE = List.of(
            new DataNeed("sensor_list", "챔버별 센서 목록",
                    "SELECT chamber, sensor_id, sensor_name\n  FROM fdc_sensor_master\n"
                            + " WHERE equipment_id = :equipment_id\n ORDER BY chamber, sensor_id",
                    List.of("CHAMBER", "SENSOR_ID", "SENSOR_NAME"),
                    List.of("센서 목록")),
            new DataNeed("recipe_steps", "레시피 STEP 구성",
                    "SELECT recipe_id, step_no, step_name, duration_sec\n  FROM fdc_recipe_step\n"
                            + " WHERE recipe_id = :recipe_id\n ORDER BY step_no",
                    List.of("RECIPE_ID", "STEP_NO", "STEP_NAME", "DURATION_SEC"),
                    List.of("레시피")));

    private static DataNeed matchDataNeed(String question) {
        String q = question.toLowerCase();
        for (DataNeed n : REQUESTABLE) {
            for (String t : n.triggers()) {
                if (q.contains(t.toLowerCase())) {
                    return n;
                }
            }
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

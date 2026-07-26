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
 * 결정적 mock LLM (LLM_BASE_URL 미설정 시). 온프렘
 * LLM 없이도 에이전트 루프·툴 호출·SSE 를 end-to-end 로 검증하기 위한 것.
 * 키워드로 툴 호출을 판단하고, 툴 결과가 오면 그 요약을 최종 답으로 돌려준다.
 */
public class MockLlm implements LlmClient {

    // 설비 ID 패턴 (예: ETCH-01, CVD-02). 대문자 2~4 + "-" + 숫자 2+.
    private static final Pattern ID_RE = Pattern.compile("\\b([A-Z]{2,4}-\\d{2,})\\b");
    // 센서 ID 패턴 (예: S-0004). 도메인 스킬(snsr_id 파라미터) 호출용.
    private static final Pattern SENSOR_RE = Pattern.compile("\\bS-\\d{3,}\\b");
    private static final String REQUEST_DATA_TOOL = "request_data";
    private static final String QUERY_SNAPSHOT_TOOL = "query_snapshot";
    // 붙여넣은 데이터를 조회하겠다는 원 질문의 신호(주입 블록은 제외하고 본다).
    // "등록 완료"는 요청 카드를 채운 뒤의 이어가기 발화 — 적재된 표를 조회해 근거로 답한다.
    private static final Pattern WANTS_SNAPSHOT_QUERY =
            Pattern.compile("이\\s*데이터|붙여넣|첨부|조회|스냅샷|등록\\s*완료|등록했", Pattern.CASE_INSENSITIVE);
    // 스키마 카탈로그의 백틱 테이블명(예: `sensor_list`)에서 조회 대상 테이블을 뽑는다.
    private static final Pattern SNAPSHOT_TABLE = Pattern.compile("`([A-Za-z0-9_]+)`");

    @Override
    public LlmTurn next(List<LlmMessage> messages, List<LlmToolSpec> tools) {
        LlmMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (last != null && "tool".equals(last.role())) {
            return new LlmTurn.Final(last.content() != null ? last.content() : "");
        }
        String userText = lastUserText(messages);
        // 후속 질문 프롬프트(ChatAgent.suggestFollowups)에는 결정적 추천을 돌려준다.
        // 이 분기가 없으면 genericAnswer 가 프롬프트를 그대로 인용해, 프롬프트 속
        // 예시 ["...", "...", "..."] 가 추천으로 파싱되는 사고가 난다.
        if (userText.contains("후속 질문 3개")) {
            return new LlmTurn.Final(followupSuggestions(messages));
        }
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

        // 붙여넣어 임시 DB로 적재된 표가 있고 원 질문이 조회를 요청하면 — query_snapshot.
        // (억제와 마찬가지로 주입 블록은 빼고 원 질문만 본다.) 스키마 카탈로그의 첫
        // 백틱 테이블명을 골라 SELECT * 로 조회한다 — 실 LLM 이 SQL 을 짜는 자리를 흉내.
        if (has(tools, QUERY_SNAPSHOT_TOOL)
                && WANTS_SNAPSHOT_QUERY.matcher(beforeInjectedBlocks(text)).find()) {
            Matcher tableMatch = SNAPSHOT_TABLE.matcher(text);
            if (tableMatch.find()) {
                return new LlmToolCall("call_1", QUERY_SNAPSHOT_TOOL, Map.of(
                        "sql", "SELECT * FROM \"" + tableMatch.group(1) + "\"",
                        "title", "조회 결과"));
            }
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

    // DB 없이 조달을 요청할 만한 데이터.
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

    /**
     * 원 질문(이 3-메시지 맥락의 첫 user)의 키워드로 다음 걸음을 고른다 —
     * 데이터 요청 왕복(sensor_list ↔ recipe_steps)이 추천 클릭만으로 이어지고,
     * 나머지는 설비 ID 조회 계열로 빠져나가게.
     */
    private static String followupSuggestions(List<LlmMessage> messages) {
        String original = messages.isEmpty() || messages.get(0).content() == null
                ? ""
                : messages.get(0).content();
        if (original.contains("센서 목록")) {
            return "[\"레시피 STEP 구성 알려줘\", \"ETCH-01 상세 정보 보여줘\", \"ETCH-01 동종 설비 알려줘\"]";
        }
        if (original.contains("레시피")) {
            return "[\"챔버별 센서 목록 보여줘\", \"ETCH-01 상세 정보 보여줘\", \"ETCH-01 셋업 이력 알려줘\"]";
        }
        return "[\"챔버별 센서 목록 보여줘\", \"레시피 STEP 구성 알려줘\", \"ETCH-01 상세 정보 보여줘\"]";
    }

    private static String genericAnswer(String text) {
        String q = text.trim();
        return (q.isEmpty() ? "" : "'" + q + "' 질문 주셨네요. ")
                + "설비 ID(예: ETCH-01)를 알려주시면 설비·챔버·센서 상세를 조회해 드립니다. "
                + "(fdc-agent-be Phase 2 — 온프렘 LLM 미설정 시 mock 응답)";
    }
}

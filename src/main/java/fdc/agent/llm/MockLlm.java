package fdc.agent.llm;

import fdc.agent.chat.ChatPrompt;
import fdc.agent.chat.DataRequestTool;
import fdc.agent.chat.InputRequestTool;
import fdc.agent.chat.SnapshotQueryTool;
import fdc.agent.contract.Role;
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
 *
 * <p><b>질문과 맥락 섹션을 따로 받는다.</b> 실 모델이라면 "무슨 값이 없는지"를
 * 판단할 자리를 목은 키워드로 흉내내는데, 그 키워드는 사용자가 실제로 한 말에서만
 * 나와야 한다 — 첨부된 표의 라벨이 질문인 척 트리거를 당기면 안 된다. 섹션 안을
 * 볼 때도 문장이 아니라 {@link ChatPrompt} 의 머리표 상수에 붙는다: 프롬프트 문구를
 * 고쳤다고 목이 깨지면, 프롬프트를 못 고치게 된다.
 */
public class MockLlm implements LlmClient {

    // 센서 ID 패턴 (예: S-0004). 도메인 스킬(snsr_id 파라미터) 호출용.
    private static final Pattern SENSOR_RE = Pattern.compile("\\bS-\\d{3,}\\b");
    // 붙여넣은 데이터를 조회하겠다는 신호 — 원 질문에서만 찾는다.
    // "등록 완료"는 요청 카드를 채운 뒤의 이어가기 발화 — 적재된 표를 조회해 근거로 답한다.
    private static final Pattern WANTS_SNAPSHOT_QUERY =
            Pattern.compile("이\\s*데이터|붙여넣|첨부|조회|스냅샷|등록\\s*완료|등록했", Pattern.CASE_INSENSITIVE);
    // 스키마 카탈로그의 백틱 테이블명(예: `sensor_list`)에서 조회 대상 테이블을 뽑는다.
    private static final Pattern SNAPSHOT_TABLE = Pattern.compile("`([A-Za-z0-9_]+)`");

    @Override
    public LlmTurn next(List<LlmMessage> messages, List<LlmToolSpec> tools) {
        LlmMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (last != null && last.role() == Role.TOOL) {
            return new LlmTurn.Final(last.content() != null ? last.content() : "");
        }
        String question = lastUserText(messages);
        // 후속 질문 프롬프트(ChatAgent.suggestFollowups)에는 결정적 추천을 돌려준다.
        // 이 분기가 없으면 genericAnswer 가 프롬프트를 그대로 인용해, 프롬프트 속
        // 예시 ["...", "...", "..."] 가 추천으로 파싱되는 사고가 난다.
        if (question.contains("후속 질문 3개")) {
            return new LlmTurn.Final(followupSuggestions(messages));
        }
        LlmToolCall call = planCall(question, contextSection(messages), tools);
        if (call != null) {
            return new LlmTurn.ToolCalls(List.of(call));
        }
        // 일반 안내의 질문 인용은 원 질문만 — 섹션까지 되읽어주면 소음이다.
        return new LlmTurn.Final(genericAnswer(question));
    }

    private static String lastUserText(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage m = messages.get(i);
            if (m.role() == Role.USER) {
                return m.content() != null ? m.content() : "";
            }
        }
        return "";
    }

    /** 에이전트가 끼운 맥락 섹션(첫 system = 시스템 프롬프트 제외) — 없으면 빈 문자열. */
    private static String contextSection(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i > 0; i--) {
            LlmMessage m = messages.get(i);
            if (m.role() == Role.SYSTEM) {
                return m.content() != null ? m.content() : "";
            }
        }
        return "";
    }

    /**
     * 키워드 기반 툴 선택. 무엇을 물었나는 {@code question} 에서, 무엇이 이미 있나는
     * {@code section} 에서 본다 — 둘을 섞지 않는 게 이 목의 유일한 규율이다.
     */
    private static LlmToolCall planCall(String question, String section, List<LlmToolSpec> tools) {
        // 센서 ID → snsr_id 파라미터를 요구하는 도메인 스킬 툴로(툴 이름 무관하게
        // 파라미터로 매칭 — 스킬이 늘어도 mock 무수정). 폼에 적힌 ID 도 주워야 하므로
        // 질문과 섹션을 함께 본다.
        Matcher sensorMatch = SENSOR_RE.matcher(question + "\n" + section);
        if (sensorMatch.find()) {
            LlmToolSpec skill = tools.stream()
                    .filter(t -> requiresParam(t, "snsr_id"))
                    .findFirst()
                    .orElse(null);
            if (skill != null) {
                return new LlmToolCall("call_1", skill.name(), Map.of("snsr_id", sensorMatch.group()));
            }
        }

        // 측정/추적 분석인데 param_index(센서)가 아직 없으면 — 그 값 하나를 입력 카드로
        // 요청한다(request_input). 값이 이미 실려 왔으면 재요청하지 않는다: [제공된 입력]
        // (채팅이 되물어 채운 값)뿐 아니라 [질의 대상](담긴 분석이 들고 온 조회 키)도
        // 같이 본다 — 에이전트는 둘 다 억제하므로, 한쪽만 보면 헛물어 답이 어색해진다.
        boolean paramProvided =
                (section.contains(ChatPrompt.SECTION_INPUTS) || section.contains(ChatPrompt.SECTION_SCOPE))
                        && section.contains("param_index");
        if (matches(question, "측정|추적|trace") && !paramProvided
                && has(tools, InputRequestTool.NAME)) {
            LlmToolSpec skill = tools.stream()
                    .filter(t -> requiresParam(t, "param_index"))
                    .findFirst()
                    .orElse(null);
            if (skill != null) {
                return new LlmToolCall("call_1", InputRequestTool.NAME, Map.of(
                        "skill", skill.name(),
                        "key", "param_index",
                        "label", "PARAM_INDEX",
                        "description", "센서 파라미터 인덱스 (센서 이름이 아님)"));
            }
        }

        // 붙여넣어 임시 DB로 적재된 표가 있고 원 질문이 조회를 요청하면 — query_snapshot.
        // 조회 대상은 섹션(스키마 카탈로그)의 첫 백틱 테이블명 — 실 LLM 이 SQL 을 짜는 자리.
        if (has(tools, SnapshotQueryTool.NAME) && WANTS_SNAPSHOT_QUERY.matcher(question).find()) {
            Matcher tableMatch = SNAPSHOT_TABLE.matcher(section);
            if (tableMatch.find()) {
                return new LlmToolCall("call_1", SnapshotQueryTool.NAME, Map.of(
                        "sql", "SELECT * FROM \"" + tableMatch.group(1) + "\"",
                        "title", "조회 결과"));
            }
        }

        // 설비/센서 ID 가 없는데 특정 데이터를 요구하면 — DB 없이 조달을 요청(request_data).
        // 실 LLM 이라면 모델이 "무엇이 없는지" 판단할 자리를, mock 은 키워드로 흉내낸다.
        // 붙여넣은 표의 라벨이 요청을 오발화시키지 않게 원 질문만 본다(억제는 에이전트가
        // queryKey 로 확정한다).
        DataNeed need = matchDataNeed(question);
        if (need != null && has(tools, DataRequestTool.NAME)) {
            return new LlmToolCall("call_1", DataRequestTool.NAME, Map.of(
                    "queryKey", need.queryKey(),
                    "label", need.label(),
                    "sql", need.sql(),
                    "columns", need.columns()));
        }
        return null;
    }

    private record DataNeed(
            String queryKey, String label, String sql, List<String> columns, List<String> triggers) {
    }

    // DB 없이 조달을 요청할 만한 데이터. 설비 계열도 여기에 있다 — 전용 조회 툴을
    // 걷어낸 뒤로 설비·동종·셋업 이력도 "요청 → 붙여넣기"로만 들어온다.
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
                    List.of("레시피")),
            new DataNeed("equipment_peers", "동종 설비 목록",
                    "SELECT eqp_id, eqp_name, model_cd\n  FROM fdc_equipment\n"
                            + " WHERE model_cd = (SELECT model_cd FROM fdc_equipment"
                            + " WHERE eqp_id = :equipment_id)\n   AND eqp_id <> :equipment_id\n"
                            + " ORDER BY eqp_id",
                    List.of("EQP_ID", "EQP_NAME", "MODEL_CD"),
                    List.of("동종", "피어")),
            new DataNeed("setup_events", "설비 셋업·정비 이력",
                    "SELECT evt_dt, evt_type_cd, evt_label\n  FROM fdc_setup_event\n"
                            + " WHERE eqp_id = :equipment_id\n ORDER BY evt_dt DESC",
                    List.of("EVT_DT", "EVT_TYPE_CD", "EVT_LABEL"),
                    List.of("셋업", "정비 이력")),
            new DataNeed("equipment_detail", "설비 기본 정보",
                    "SELECT eqp_id, eqp_name, model_cd, vendor, use_yn\n  FROM fdc_equipment\n"
                            + " WHERE eqp_id = :equipment_id",
                    List.of("EQP_ID", "EQP_NAME", "MODEL_CD", "VENDOR", "USE_YN"),
                    List.of("설비 정보", "설비 상세", "상세 정보")));

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
     * 데이터 요청 왕복(sensor_list ↔ recipe_steps ↔ 설비 계열)이 추천 클릭만으로
     * 이어지게.
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
                + "설비 ID(예: ETCH-01)나 센서 ID(예: S-0004)와 무엇을 보고 싶은지 알려주시면, "
                + "필요한 데이터를 조회 SQL 과 함께 요청해 드립니다. "
                + "(fdc-agent-be Phase 2 — 온프렘 LLM 미설정 시 mock 응답)";
    }
}

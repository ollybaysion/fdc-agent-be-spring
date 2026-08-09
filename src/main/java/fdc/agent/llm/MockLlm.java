package fdc.agent.llm;

import fdc.agent.chat.ChatPrompt;
import fdc.agent.chat.ChoiceRequestTool;
import fdc.agent.chat.NarrationPrompt;
import fdc.agent.chat.InputRequestTool;
import fdc.agent.chat.RetrieveDataTool;
import fdc.agent.chat.SnapshotQueryTool;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmToolSpec;
import fdc.agent.llm.LlmTypes.LlmTurn;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    // 진행 상황 섹션이 적어 준 다음 한 걸음 — 그대로 베껴 부르면 절차가 이어진다.
    private static final Pattern NEXT_STEP =
            Pattern.compile("skill=\"([^\"]+)\",\\s*args=(\\{[^}]*})");
    private static final Pattern JSON_PAIR = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
    // 사용자가 "직접 실행할 SQL 을 달라"고 말한 신호 — 조달 요청의 시작.
    private static final Pattern WANTS_SQL =
            Pattern.compile("SQL|쿼리\\s*(줘|주세요|요청)|조달|요청\\s*카드", Pattern.CASE_INSENSITIVE);
    // retrieve_data 툴 설명에 실린 스킬 목록 한 줄 — "- 스킬명 (인자: a, b)".
    private static final Pattern CATALOG_LINE =
            Pattern.compile("(?m)^- (\\S+) \\(인자: ([^)]*)\\)\\s*$");
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
            String content = last.content() != null ? last.content() : "";
            // 조달 툴은 사람이 읽을 문장이 아니라 원장(JSON)을 돌려준다 — 실 모델이
            // 해석해 쓸 자리를 목은 결정적 요약으로 흉내낸다. 되읽기로는 화면에
            // JSON 이 그대로 나간다.
            return new LlmTurn.Final(RetrieveDataTool.NAME.equals(last.name())
                    ? retrievalSummary(content)
                    : content);
        }
        String question = lastUserText(messages);
        // 후속 질문 프롬프트(ChatAgent.suggestFollowups)에는 결정적 추천을 돌려준다.
        // 이 분기가 없으면 genericAnswer 가 프롬프트를 그대로 인용해, 프롬프트 속
        // 예시 ["...", "...", "..."] 가 추천으로 파싱되는 사고가 난다.
        if (question.contains("후속 질문 3개")) {
            return new LlmTurn.Final(followupSuggestions(messages));
        }
        // 종결 서술 지시(/chat/data) — 실 모델이 해석·결론을 쓸 자리를 목은 판정 턴을
        // 결정적으로 되읽어 흉내낸다. 제한망 1차 배포가 바로 이 경로다(#38 T6).
        // 감지는 문장이 아니라 지시 상수에 붙는다.
        if (question.contains(NarrationPrompt.NARRATE_INSTRUCTION)) {
            return new LlmTurn.Final(narration(lastAssistantText(messages)));
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

    /**
     * {@code retrieve_data} 원장 → 사람이 읽을 한 문단. 실 모델이라면 여기서 도착한
     * 값을 해석하겠지만, 목은 <b>무엇을 요청했고 무엇이 막혔나</b>만 결정적으로 옮긴다.
     */
    static String retrievalSummary(String json) {
        List<String> lines = new ArrayList<>();
        for (String what : jsonValues(json, "requested", "what")) {
            lines.add("- " + what);
        }
        if (!lines.isEmpty()) {
            lines.add(0, "데이터 요청을 등록했습니다. 데이터 패널 카드의 SQL 을 실행해 결과를 붙여넣어"
                    + " 주세요 — 조회 결과가 없으면 \"결과 없음\"으로 등록하시면 그것도 사실로 받습니다.");
            return String.join("\n", lines);
        }
        List<String> stuck = jsonValues(json, "blocked", "what");
        if (!stuck.isEmpty()) {
            return "지금 조달할 수 있는 것이 없습니다: " + String.join(", ", stuck)
                    + ". 확인되지 않은 것은 확인되지 않았다고 답합니다.";
        }
        return "필요한 데이터가 모두 확인됐습니다.";
    }

    /**
     * {@code {"<section>":[{…,"<field>":"값"}]}} 에서 값들만. 목이 JSON 을 읽는 유일한
     * 자리라 파서를 들이지 않는다 — 섹션 뒤 첫 대괄호 블록 안에서만 찾는다.
     */
    private static List<String> jsonValues(String json, String section, String field) {
        int at = json.indexOf("\"" + section + "\"");
        if (at < 0) {
            return List.of();
        }
        int open = json.indexOf('[', at);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json.substring(open, close));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 마지막 assistant 발화 — 종결 서술 경로에서는 판정 턴이다. 없으면 빈 문자열. */
    private static String lastAssistantText(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage m = messages.get(i);
            if (m.role() == Role.ASSISTANT && m.content() != null && !m.content().isBlank()) {
                return m.content();
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
        // 진행 중인 절차가 다음 걸음을 적어 뒀으면 그걸 이어 부른다 — 실 모델이 판단할
        // "계속할까"를 목은 "적혀 있으면 간다"로 흉내낸다. 0행으로 끝난 절차에는 다음
        // 걸음이 적히지 않으므로 여기서 자연히 멈춘다(억제가 아니라 부재로).
        if (has(tools, RetrieveDataTool.NAME)) {
            Matcher next = NEXT_STEP.matcher(section);
            if (next.find()) {
                return new LlmToolCall("call_1", RetrieveDataTool.NAME, Map.of(
                        "skill", next.group(1), "args", parseJsonPairs(next.group(2))));
            }
        }

        // 사용자가 실행할 SQL 을 청하면 — 풀에서 인자를 채울 수 있는 첫 단계를 요청한다.
        // 어느 스킬인지는 목이 모른다: 툴 스펙에 실린 카탈로그에서 인자 이름으로 찾는다
        // (스킬이 늘거나 이름이 바뀌어도 목은 무수정).
        Matcher sqlSensor = SENSOR_RE.matcher(question);
        if (has(tools, RetrieveDataTool.NAME) && WANTS_SQL.matcher(question).find()
                && sqlSensor.find()) {
            String skill = skillTaking(tools, "snsr_id");
            if (skill != null) {
                return new LlmToolCall("call_1", RetrieveDataTool.NAME, Map.of(
                        "skill", skill, "args", Map.of("snsr_id", sqlSensor.group())));
            }
        }

        // 질문에 서로 다른 센서 ID 가 둘 이상 있으면 — 어느 것을 볼지 선택 카드로 묻는다
        // (다중 선택이 자연스러운 사례를 흉내낸다). 실 모델이 "후보를 2~10개로 좁혔다"고
        // 판단할 자리를, 목은 질문에 이미 여러 후보가 나열돼 있다는 신호로 흉내낸다.
        List<String> sensors = distinctSensors(question);
        if (sensors.size() >= 2 && has(tools, ChoiceRequestTool.NAME)) {
            List<Map<String, String>> options = sensors.stream()
                    .map(id -> Map.of("label", id))
                    .toList();
            return new LlmToolCall("call_1", ChoiceRequestTool.NAME, Map.of(
                    "question", "어느 센서를 분석할까요?",
                    "options", options,
                    "multiSelect", true));
        }

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

        return null;
    }

    /**
     * <b>딱 이 인자들만 요구하는 스킬</b>의 이름. 목록은 retrieve_data 툴 스펙의
     * skill 설명에 실려 오므로 목은 스킬 이름을 하나도 알 필요가 없다 — 스킬이
     * 늘거나 이름이 바뀌어도 무수정이다.
     *
     * <p>조달 단위가 need 로 바뀌면서 목이 고를 것도 "어느 조회"가 아니라 "어느
     * 절차"가 됐다. 무엇을 먼저 돌릴지는 서버가 판정으로 정한다.
     */
    private static String skillTaking(List<LlmToolSpec> tools, String... argNames) {
        Matcher line = CATALOG_LINE.matcher(skillDescription(tools));
        List<String> wanted = List.of(argNames);
        while (line.find()) {
            List<String> args = Arrays.stream(line.group(2).split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (args.equals(wanted)) {
                return line.group(1);
            }
        }
        return null;
    }

    /** retrieve_data 툴 스펙의 skill 설명(= 등재된 스킬 목록). 툴이 없으면 빈 문자열. */
    private static String skillDescription(List<LlmToolSpec> tools) {
        for (LlmToolSpec tool : tools) {
            if (!tool.name().equals(RetrieveDataTool.NAME)) {
                continue;
            }
            if (tool.parameters().get("properties") instanceof Map<?, ?> props
                    && props.get("skill") instanceof Map<?, ?> skill) {
                return String.valueOf(skill.get("description"));
            }
        }
        return "";
    }

    /** {@code {"a":"1","b":"2"}} → 맵. 목이 진행 섹션의 args 를 그대로 베낄 때만 쓴다. */
    private static Map<String, Object> parseJsonPairs(String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        Matcher m = JSON_PAIR.matcher(json);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    /** 텍스트에 등장한 서로 다른 센서 ID — 등장 순서 그대로, 중복은 한 번만. */
    private static List<String> distinctSensors(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = SENSOR_RE.matcher(text);
        while (m.find()) {
            String id = m.group();
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
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
     * 원 질문(이 3-메시지 맥락의 첫 user)의 키워드로 다음 걸음을 고른다 — 조달 왕복
     * (요청 → 붙여넣기 → 다음 단계)이 추천 클릭만으로 이어지게.
     */
    private static String followupSuggestions(List<LlmMessage> messages) {
        String original = messages.isEmpty() || messages.get(0).content() == null
                ? ""
                : messages.get(0).content();
        if (WANTS_SQL.matcher(original).find()) {
            return "[\"등록 완료 — 이어서 분석해줘\", \"다음 단계도 SQL 로 요청해줘\","
                    + " \"조회 결과가 없으면 어떻게 되나요?\"]";
        }
        return "[\"S-0004 조회 SQL 로 요청해줘\", \"CVD-01 측정 분석해줘\", \"이 데이터로 정리해줘\"]";
    }

    /**
     * 판정 턴({@link NarrationPrompt} 의 마지막 assistant)을 되읽어 결정적 종결
     * 서술을 만든다 — 목은 해석을 못 하니 결정론 관찰을 그대로 옮긴다. 도착 데이터
     * 자체는 tool 메시지에 있고 목이 그걸 요약할 방법은 없다.
     */
    private static String narration(String verdict) {
        List<String> lines = verdict.lines().filter(l -> l.startsWith("- ")).toList();
        return "요청하신 조회 절차가 완료됐습니다.\n" + String.join("\n", lines)
                + "\n\n값 전문은 데이터 패널에서 확인하세요. (온프렘 LLM 미설정 시 mock 서술)";
    }

    private static String genericAnswer(String text) {
        String q = text.trim();
        return (q.isEmpty() ? "" : "'" + q + "' 질문 주셨네요. ")
                + "설비 ID(예: ETCH-01)나 센서 ID(예: S-0004)와 무엇을 보고 싶은지 알려주시면, "
                + "등재된 조회 목록에서 골라 실행 가능한 SQL 과 함께 요청해 드립니다. "
                + "(fdc-agent-be Phase 2 — 온프렘 LLM 미설정 시 mock 응답)";
    }
}

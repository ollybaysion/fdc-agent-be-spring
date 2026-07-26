package fdc.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.AgentTool.ToolResult;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.ChatTable;
import fdc.agent.contract.DataRequest;
import fdc.agent.contract.FinishReason;
import fdc.agent.contract.InputRequest;
import fdc.agent.contract.QueryScope;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmToolSpec;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.skills.SkillQuery;
import fdc.agent.skills.SkillRegistry;
import fdc.agent.skills.SkillSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 에이전트 루프. LLM 이 툴을 부르면 실행해 결과를 되먹이고,
 * 최종 답이 나오면 텍스트 + 수집한 표를 돌려준다.
 * LLM(mock/openai)·스킬 출처(번들/akg)는 seam 뒤라 이 루프는 무지.
 *
 * 툴 = 도메인 스킬(spec 컴파일) + query_snapshot(붙여넣은 표). 스킬로 닿지
 * 않는 데이터는 실행하지 않고 request_data 로 사용자에게 조달을 요청한다.
 */
public class ChatAgent {

    private static final int MAX_STEPS = 4;

    /** 조달 요청 툴 이름 — 실 조회 툴과 달리 실행하지 않고 요청으로 수집한다. */
    private static final String REQUEST_DATA_TOOL = "request_data";

    /** 입력 요청 툴 이름 — 스킬 인자(스칼라) 하나를 사용자에게 입력받는다(실행 안 함). */
    private static final String REQUEST_INPUT_TOOL = "request_input";

    /** 붙여넣은 스냅샷 임시 DB 를 SELECT 로 조회하는 툴 이름(Design B). */
    private static final String QUERY_SNAPSHOT_TOOL = "query_snapshot";

    private static final String SYSTEM_PROMPT = String.join(" ",
            "당신은 반도체 설비 이상탐지(FDC) 분석 어시스턴트다.",
            "설비/챔버/센서 데이터가 필요하면 반드시 제공된 툴을 호출해 사실을 확인하고,",
            "조회하지 않은 값은 지어내지 않는다.",
            "센서 데이터 분석에는 설비·PARAM_INDEX·기간이 모두 필요하다 — 폼 입력이나 대화에서",
            "이 중 빠진 게 있으면 추측하지 말고 무엇을 더 입력해야 하는지 사용자에게 되물어라.",
            "DB 에 직접 조회할 수 없어 필요한 데이터를 얻지 못하면, 값을 지어내지 말고 request_data 툴로",
            "사용자에게 조달을 요청하라 — 안정적인 snake_case queryKey 와, 가능하면 실행할 SQL 을 함께 준다.",
            "이미 [제공된 데이터]로 받은 것(같은 queryKey)은 다시 요청하지 않는다.",
            "스킬에 필요한 값(설비·PARAM_INDEX 등)이 폼·대화에 없으면, 프로즈로 길게 되묻지 말고 request_input 툴로",
            "그 값 하나를 입력 카드로 요청하라 — 진행하려는 스킬(툴) 이름을 skill 로, 인자 이름을 key 로 준다.",
            "이미 [제공된 입력]으로 받은 값은 다시 요청하지 말고 그 스킬을 그 값으로 이어서 진행한다.",
            "사용자가 붙여넣어 임시 DB(SQLite)로 적재된 표가 있으면, 값을 추측하지 말고 query_snapshot 툴에",
            "SELECT 문을 주어 조회한 결과를 근거로 삼는다(사용 가능한 테이블·컬럼은 [제공된 데이터]에 있다).",
            // FE(MessageBubble)는 답변을 Markdown(GFM)으로 렌더한다 — 서식을 명시하지
            // 않으면 굵게/목록 등이 깨진다. raw HTML 은 sanitize 로 제거되므로 쓰지 않는다.
            "답변은 한국어로 간결하게 작성하고, 서식은 Markdown(GFM)으로 한다 —",
            "강조는 **굵게**, 구조가 필요하면 제목(##)·목록(-)·인용(>)을 쓰되 원시 HTML 은 쓰지 않는다.",
            "조회한 데이터 표는 화면에 따로 표시되니 본문에 같은 표를 다시 그리지 말고 해석·요약에 집중한다.");

    private static final String FOLLOWUP_PROMPT =
            "위 답변에 이어 사용자가 이 설비 분석 맥락에서 물을 만한 후속 질문 3개를 한국어로 제안하라. "
                    + "JSON 문자열 배열로만, 다른 설명 없이. 예: [\"...\", \"...\", \"...\"]";

    private static final Pattern JSON_ARRAY = Pattern.compile("\\[[\\s\\S]*\\]");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 대화 히스토리 한 줄(FE 계약 느슨하게 수용). */
    public record HistoryMessage(Role role, String content) {
    }

    /** FE 폼 입력(설비 정보 + 시간 범위). 계약 느슨하게 — BE 는 요약만 한다. */
    public record FormContext(List<ContextRow> context, TimeRange timeRange) {
        public record ContextRow(String equipment, List<Chamber> chambers) {
        }

        public record Chamber(List<Sensor> sensors) {
        }

        public record Sensor(String name) {
        }

        public record TimeRange(String start, String end) {
        }
    }

    /**
     * finishReason: "stop" | "length". recommendQuestion 은 실패/미지원 시 빈 배열.
     * dataRequests 는 이 응답에서 조달을 요청한 데이터(없으면 빈 배열).
     * inputRequests 는 이 응답에서 입력 카드로 요청한 스칼라 값(없으면 빈 배열).
     */
    public record AgentResult(
            String text, List<ChatTable> tables, FinishReason finishReason,
            List<String> recommendQuestion, List<DataRequest> dataRequests,
            List<InputRequest> inputRequests) {
    }

    private final LlmClient llm;
    private final SkillQuery skillQuery;
    private final SkillSource skillSource;

    public ChatAgent(LlmClient llm, SkillQuery skillQuery) {
        this(llm, skillQuery, SkillRegistry::bundledSpecs);
    }

    /** skillSource = 스킬 spec 출처 seam — classpath 번들 또는 akg 허브(#8). */
    public ChatAgent(LlmClient llm, SkillQuery skillQuery, SkillSource skillSource) {
        this.llm = llm;
        this.skillQuery = skillQuery;
        this.skillSource = skillSource;
    }

    public AgentResult run(List<HistoryMessage> history, FormContext formContext) {
        return run(history, formContext, null, null);
    }

    public AgentResult run(
            List<HistoryMessage> history, FormContext formContext, List<ChatDataSnapshot> dataSnapshots) {
        return run(history, formContext, dataSnapshots, null);
    }

    /**
     * @param inputs 사용자가 입력 카드로 채워 되보낸 스칼라 값 — 스킬로 네임스페이스된
     *     {@code {skill: {key: value}}}. 프롬프트에 주입되고, 같은 (skill,key) 는
     *     {@code request_input} 재요청이 억제된다(왕복 종료 보장). 없으면 null.
     */
    public AgentResult run(
            List<HistoryMessage> history, FormContext formContext,
            List<ChatDataSnapshot> dataSnapshots, Map<String, Map<String, String>> inputs) {
        return run(history, formContext, dataSnapshots, inputs, null);
    }

    /**
     * @param scope 사용자가 질의 대상 트레이에 담은 것 — 이 질문이 무엇을 놓고 하는
     *     질문인지. 담긴 분석의 조회 키도 여기 실려 오고, 그 (skill,key) 역시
     *     {@code request_input} 재요청이 억제된다. 담긴 게 없으면 null.
     */
    public AgentResult run(
            List<HistoryMessage> history, FormContext formContext,
            List<ChatDataSnapshot> dataSnapshots, Map<String, Map<String, String>> inputs,
            QueryScope scope) {
        // 붙여넣은 스냅샷(행 있음) → 요청 단위 인메모리 SQLite(Design B). 없으면 null.
        // 요청이 끝나면 연결을 닫는다(finally) — 여러 반환점을 감싸려 루프를 분리한다.
        SnapshotDb snapshotDb = SnapshotDb.build(dataSnapshots);
        try {
            return runLoop(history, formContext, dataSnapshots, snapshotDb, inputs, scope);
        } finally {
            if (snapshotDb != null) {
                snapshotDb.close();
            }
        }
    }

    private AgentResult runLoop(
            List<HistoryMessage> history, FormContext formContext,
            List<ChatDataSnapshot> dataSnapshots, SnapshotDb snapshotDb,
            Map<String, Map<String, String>> providedInputs, QueryScope scope) {
        // 도메인 스킬 툴(explain-sensor / trace-reading 등 자동 로드)
        // + (붙여넣은 스냅샷이 있으면) query_snapshot 툴.
        List<AgentTool> tools = new ArrayList<>();
        tools.addAll(SkillRegistry.compile(skillSource.specs(), skillQuery));
        if (snapshotDb != null) {
            tools.add(querySnapshotTool(snapshotDb));
        }
        Map<String, AgentTool> toolByName = new LinkedHashMap<>();
        tools.forEach(t -> toolByName.put(t.name(), t));
        List<LlmToolSpec> specs = new ArrayList<>(tools.stream()
                .map(t -> new LlmToolSpec(t.name(), t.description(), t.parameters()))
                .toList());
        // 조달 요청 툴 + 입력 요청 툴 — 실행 툴이 아니라 "이게 필요하다"를 수집하는 특수 툴.
        specs.add(requestDataSpec());
        specs.add(requestInputSpec());

        // 이미 제공된 스냅샷의 queryKey — 같은 키의 요청은 억제(왕복 종료 보장).
        Set<String> suppliedKeys = new HashSet<>();
        if (dataSnapshots != null) {
            for (ChatDataSnapshot s : dataSnapshots) {
                if (s.queryKey() != null && !s.queryKey().isBlank()) {
                    suppliedKeys.add(s.queryKey());
                }
            }
        }
        List<DataRequest> dataRequests = new ArrayList<>();
        Set<String> requestedKeys = new HashSet<>();

        // 이미 채워진 입력(skill.key) — 같은 입력 요청은 억제. request_input 도 같은
        // 왕복 종료 규율을 따른다: 제공된 값은 다시 카드로 내보내지 않는다.
        // 담긴 분석의 조회 키도 이미 채워진 값이다 — 진입 폼에서 사람이 정했으니
        // 채팅이 그걸 다시 카드로 물으면 같은 값을 두 번 묻는 셈이 된다.
        Set<String> providedInputKeys = providedInputKeys(providedInputs);
        providedInputKeys.addAll(scopeInputKeys(scope));
        List<InputRequest> inputRequests = new ArrayList<>();
        Set<String> requestedInputKeys = new HashSet<>();

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.of(Role.SYSTEM, SYSTEM_PROMPT));
        List<LlmMessage> hist = new ArrayList<>(history.stream()
                .map(m -> LlmMessage.of(
                        m.role() == Role.ASSISTANT ? Role.ASSISTANT : Role.USER,
                        m.content() != null ? m.content() : ""))
                .toList());
        // 폼 컨텍스트 + 사용자가 붙여넣은 데이터 스냅샷은 질문에 덧붙이지 않고
        // 마지막 사용자 메시지 **앞의 별도 system 섹션**으로 끼운다(사용자 결정
        // 2026-07-24 — 질문과 데이터 맥락의 분리). 질문 텍스트가 오염되지 않아
        // 후속 추천·mock 키워드가 원 질문만 보고, 실 LLM 에도 첨부가 지시가
        // 아니라 맥락임이 역할(role)로 드러난다. 붙여넣은 값 자체는 여기 없다 —
        // 행은 임시 SQLite 로 가고 섹션에는 스키마 카탈로그만 실린다(Design B).
        String note = joinNotes(
                formatQueryScope(scope),
                formatFormContext(formContext), formatDataSnapshots(dataSnapshots, snapshotDb),
                formatProvidedInputs(providedInputs));
        if (note != null) {
            int lastUser = -1;
            for (int i = hist.size() - 1; i >= 0; i--) {
                if (hist.get(i).role() == Role.USER) {
                    lastUser = i;
                    break;
                }
            }
            if (lastUser >= 0) {
                hist.add(lastUser, LlmMessage.of(Role.SYSTEM, note));
            } else {
                hist.add(LlmMessage.of(Role.USER, note));
            }
        }
        messages.addAll(hist);

        List<ChatTable> tables = new ArrayList<>();

        for (int step = 0; step < MAX_STEPS; step++) {
            LlmTurn turn = llm.next(messages, specs);
            if (turn instanceof LlmTurn.Final fin) {
                List<String> recommendQuestion = suggestFollowups(messages, fin.content());
                return new AgentResult(fin.content(), tables, FinishReason.STOP,
                        recommendQuestion, dataRequests, inputRequests);
            }

            List<LlmToolCall> toolCalls = ((LlmTurn.ToolCalls) turn).toolCalls();
            messages.add(LlmMessage.assistantToolCalls(toolCalls));
            for (LlmToolCall call : toolCalls) {
                if (REQUEST_DATA_TOOL.equals(call.name())) {
                    String ack = collectDataRequest(
                            call.arguments(), suppliedKeys, requestedKeys, dataRequests);
                    messages.add(LlmMessage.toolResult(call.id(), call.name(), ack));
                    continue;
                }
                if (REQUEST_INPUT_TOOL.equals(call.name())) {
                    String ack = collectInputRequest(
                            call.arguments(), providedInputKeys, requestedInputKeys, inputRequests);
                    messages.add(LlmMessage.toolResult(call.id(), call.name(), ack));
                    continue;
                }
                AgentTool tool = toolByName.get(call.name());
                ToolResult result = tool != null
                        ? tool.execute().run(call.arguments())
                        : ToolResult.of("알 수 없는 툴: " + call.name());
                if (result.tables() != null) {
                    tables.addAll(result.tables());
                }
                messages.add(LlmMessage.toolResult(call.id(), call.name(), result.summary()));
            }
        }

        // 스텝 한도 초과 — 마지막 툴 요약이라도 돌려준다.
        String lastTool = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.TOOL) {
                lastTool = messages.get(i).content();
                break;
            }
        }
        return new AgentResult(
                lastTool != null ? lastTool : "요청을 완료하지 못했습니다. 좀 더 구체적으로 질문해 주세요.",
                tables, FinishReason.LENGTH, List.of(), dataRequests, inputRequests);
    }

    /**
     * request_data 툴 호출을 조달 요청으로 수집한다(실행하지 않는다). 이미 제공된
     * 스냅샷과 같은 queryKey 이거나 이번 응답에서 이미 요청한 키면 카드로 내보내지
     * 않는다 — 왕복이 끝나게 하는 억제다. LLM 에 되먹일 확인 문구를 돌려준다.
     */
    private static String collectDataRequest(
            Map<String, Object> args, Set<String> suppliedKeys,
            Set<String> requestedKeys, List<DataRequest> out) {
        DataRequest req = parseDataRequest(args);
        if (req == null) {
            return "데이터 요청이 형식에 맞지 않아 등록하지 못했습니다(queryKey·label 필요).";
        }
        if (suppliedKeys.contains(req.queryKey()) || !requestedKeys.add(req.queryKey())) {
            return "이미 제공되었거나 요청된 데이터입니다: " + req.label() + " — 그대로 분석을 이어가라.";
        }
        out.add(req);
        return "데이터 요청을 등록했습니다: " + req.label()
                + ". 데이터 패널 카드의 SQL 을 실행해 결과를 붙여넣어 등록하고, 채팅에 \"등록 완료\"라고"
                + " 알려주시면 그 데이터로 이어서 분석합니다. 없는 값은 지어내지 않습니다.";
    }

    /** request_data 인자(느슨하게 수용)를 DataRequest 로. queryKey·label 없으면 null. */
    private static DataRequest parseDataRequest(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        String queryKey = asTrimmed(args.get("queryKey"));
        String label = asTrimmed(args.get("label"));
        if (queryKey == null || label == null) {
            return null;
        }
        String sql = asTrimmed(args.get("sql"));
        List<String> columns = null;
        if (args.get("columns") instanceof List<?> raw) {
            List<String> cols = new ArrayList<>();
            for (Object o : raw) {
                String c = asTrimmed(o);
                if (c != null) {
                    cols.add(c);
                }
            }
            columns = cols.isEmpty() ? null : cols;
        }
        return new DataRequest(queryKey, label, sql, columns);
    }

    /** null·공백은 null, 그 외 trim 한 문자열. */
    private static String asTrimmed(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * request_data 툴의 LLM 노출 정의. 조회 툴과 달리 실행되지 않고 조달 요청으로
     * 수집되며, done 페이로드의 dataRequests → FE 요청 카드로 렌더된다.
     */
    private static LlmToolSpec requestDataSpec() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("queryKey", Map.of("type", "string", "description",
                "이 데이터를 충족할 스냅샷의 안정 키(snake_case). 사용자가 붙여넣으면 이 키로 등록돼 "
                        + "다음 요청에서 충족 여부를 판정한다."));
        props.put("label", Map.of("type", "string", "description",
                "사람이 읽는 설명 — 무슨 데이터가 왜 필요한지."));
        props.put("sql", Map.of("type", "string", "description",
                "사용자가 실행할 SQL(가능하면). 화면에 복사 버튼과 함께 표시된다."));
        props.put("columns", Map.of(
                "type", "array", "items", Map.of("type", "string"), "description",
                "기대 컬럼(선택) — 사용자가 맞는 결과를 붙여넣었는지 가늠용."));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("queryKey", "label"));
        return new LlmToolSpec(REQUEST_DATA_TOOL,
                "DB 에 직접 조회할 수 없어 필요한 데이터를 얻지 못할 때, 값을 지어내지 말고 이 툴로 "
                        + "사용자에게 조달을 요청한다. 이미 [제공된 데이터]로 받은 것은 다시 요청하지 않는다.",
                params);
    }

    /**
     * request_input 툴 호출을 입력 요청으로 수집한다(실행하지 않는다). 이미 채워진
     * (skill,key) 이거나 이번 응답에서 이미 요청한 것이면 카드로 내보내지 않는다 —
     * request_data 와 같은 왕복 종료 억제다. LLM 에 되먹일 확인 문구를 돌려준다.
     */
    private static String collectInputRequest(
            Map<String, Object> args, Set<String> providedKeys,
            Set<String> requestedKeys, List<InputRequest> out) {
        InputRequest req = parseInputRequest(args);
        if (req == null) {
            return "입력 요청이 형식에 맞지 않아 등록하지 못했습니다(skill·key·label 필요).";
        }
        String dedup = inputKey(req.skill(), req.key());
        if (providedKeys.contains(dedup) || !requestedKeys.add(dedup)) {
            return "이미 제공되었거나 요청된 입력입니다: " + req.label() + " — 그 값으로 이어서 진행하라.";
        }
        out.add(req);
        return "입력 요청을 등록했습니다: " + req.label()
                + ". 데이터 패널의 입력 카드에 값을 넣어 주시면 그 값으로 이어서 분석합니다. 없는 값은 지어내지 않습니다.";
    }

    /** request_input 인자(느슨하게 수용)를 InputRequest 로. skill·key·label 없으면 null. */
    private static InputRequest parseInputRequest(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        String skill = asTrimmed(args.get("skill"));
        String key = asTrimmed(args.get("key"));
        String label = asTrimmed(args.get("label"));
        if (skill == null || key == null || label == null) {
            return null;
        }
        return new InputRequest(skill, key, label, asTrimmed(args.get("description")));
    }

    /**
     * request_input 툴의 LLM 노출 정의. 실행되지 않고 입력 요청으로 수집되며,
     * done 페이로드의 inputRequests → FE 입력 카드로 렌더된다. 채운 값은 다음 요청의
     * inputs[skill][key] 로 회신돼 프롬프트에 주입된다.
     */
    private static LlmToolSpec requestInputSpec() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("skill", Map.of("type", "string", "description",
                "이 값이 필요한 스킬(툴) 이름 — 지금 진행하려는 그 스킬. 회신이 이 스킬로 묶인다."));
        props.put("key", Map.of("type", "string", "description",
                "필요한 스킬 인자 이름(예: param_index). 사용자가 채우면 이 이름으로 회신된다."));
        props.put("label", Map.of("type", "string", "description",
                "사람이 읽는 입력 이름 — 화면 카드에 표시된다(예: PARAM_INDEX)."));
        props.put("description", Map.of("type", "string", "description",
                "무슨 값을 넣어야 하는지 짧은 안내(선택)."));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("skill", "key", "label"));
        return new LlmToolSpec(REQUEST_INPUT_TOOL,
                "스킬에 필요한 값(설비·PARAM_INDEX 등)이 폼·대화에 없을 때, 프로즈로 되묻지 말고 이 "
                        + "툴로 그 값 하나를 사용자에게 입력받는다. 이미 [제공된 입력]으로 받은 것은 다시 요청하지 않는다.",
                params);
    }

    /** 억제 키 — (skill, key) 를 한 문자열로. 회신 네임스페이스와 같은 규칙. */
    private static String inputKey(String skill, String key) {
        return skill + " " + key;
    }

    /** 회신된 inputs 에서 이미 채워진 (skill,key) 집합 — 억제용. */
    private static Set<String> providedInputKeys(Map<String, Map<String, String>> inputs) {
        Set<String> keys = new HashSet<>();
        if (inputs == null) {
            return keys;
        }
        for (Map.Entry<String, Map<String, String>> e : inputs.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            for (Map.Entry<String, String> kv : e.getValue().entrySet()) {
                if (kv.getKey() != null && kv.getValue() != null && !kv.getValue().isBlank()) {
                    keys.add(inputKey(e.getKey(), kv.getKey()));
                }
            }
        }
        return keys;
    }

    /** 담긴 분석들이 이미 채워 온 (skill,key) — 억제용. */
    private static Set<String> scopeInputKeys(QueryScope scope) {
        Set<String> keys = new HashSet<>();
        if (scope == null || scope.analyses() == null) {
            return keys;
        }
        for (QueryScope.Analysis a : scope.analyses()) {
            if (a == null || a.skill() == null || a.inputs() == null) {
                continue;
            }
            for (Map.Entry<String, String> kv : a.inputs().entrySet()) {
                if (kv.getKey() != null && kv.getValue() != null && !kv.getValue().isBlank()) {
                    keys.add(inputKey(a.skill(), kv.getKey()));
                }
            }
        }
        return keys;
    }

    /**
     * 사용자가 담은 질의 대상을 LLM 이 읽을 한 블록으로 — 이 질문이 무엇을 놓고
     * 하는 질문인지. 담긴 게 없으면 null(주입 안 함): 스코프는 좁히는 장치이지
     * 필수 관문이 아니라, 안 담았다고 답을 막지 않는다.
     *
     * <p>설비 줄과 분석 줄이 한 목록에 섞이는 건 의도다 — 둘은 성격이 같고 넓이만
     * 다르다. 분석 줄에는 그 분석의 조회 키를 같이 적는다: 같은 스킬이 두 설비에
     * 걸렸을 때 어느 값이 어느 쪽 것인지는 그렇게만 구분된다.
     */
    static String formatQueryScope(QueryScope scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("[질의 대상 — 사용자가 담은 것]");
        if (scope.equipments() != null) {
            for (String eq : scope.equipments()) {
                if (eq != null && !eq.isBlank()) {
                    lines.add("- 설비 " + eq.trim() + " (전체)");
                }
            }
        }
        if (scope.analyses() != null) {
            for (QueryScope.Analysis a : scope.analyses()) {
                if (a == null || a.equipment() == null || a.equipment().isBlank()) {
                    continue;
                }
                lines.add("- 설비 " + a.equipment().trim() + " · " + scopeAnalysisLabel(a));
            }
        }
        if (lines.size() == 1) {
            return null;
        }
        lines.add("이 질문은 위 대상에 관한 것이다 — 담기지 않은 설비는 답의 근거로 쓰지 말라.");
        return String.join("\n", lines);
    }

    /** 분석 줄의 꼬리 — "측정 분포 (fdc_trace_reading; days=30)". */
    private static String scopeAnalysisLabel(QueryScope.Analysis a) {
        String focus = a.focus() != null && !a.focus().isBlank() ? a.focus().trim() : a.skill();
        List<String> detail = new ArrayList<>();
        if (a.skill() != null && !a.skill().isBlank()) {
            detail.add(a.skill().trim());
        }
        if (a.inputs() != null) {
            for (Map.Entry<String, String> kv : a.inputs().entrySet()) {
                if (kv.getKey() != null && kv.getValue() != null && !kv.getValue().isBlank()) {
                    detail.add(kv.getKey() + "=" + kv.getValue().trim());
                }
            }
        }
        return detail.isEmpty()
                ? String.valueOf(focus)
                : focus + " (" + String.join("; ", detail) + ")";
    }

    /**
     * 사용자가 입력 카드로 채워 되보낸 스칼라 값을 LLM 이 읽을 한 블록으로 —
     * 어느 스킬의 무슨 값인지 + "이미 있으니 그 스킬을 이어서 진행하라". 아무것도
     * 없으면 null(주입 안 함). request_input 억제와 짝이라, 여기 실린 값은 다시
     * 카드로 요청되지 않는다.
     */
    static String formatProvidedInputs(Map<String, Map<String, String>> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("[제공된 입력 — 사용자 입력]");
        boolean any = false;
        for (Map.Entry<String, Map<String, String>> e : inputs.entrySet()) {
            String skill = e.getKey();
            Map<String, String> kv = e.getValue();
            if (skill == null || kv == null) {
                continue;
            }
            for (Map.Entry<String, String> kvE : kv.entrySet()) {
                String k = kvE.getKey();
                String v = kvE.getValue();
                if (k == null || v == null || v.isBlank()) {
                    continue;
                }
                lines.add("- " + skill + "." + k + " = " + v.trim());
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        lines.add("이 값들은 이미 제공됐다 — 다시 request_input 하지 말고 해당 스킬을 그 값으로 이어서 진행하라.");
        return String.join("\n", lines);
    }

    /**
     * 최종 답변에 이어질 추천 후속 질문을 LLM 에 한 번 더 물어 배열로. best-effort
     * (실패·미지원 시 빈 배열) — 답변 자체는 이 결과와 무관하게 이미 확정.
     * 툴 없이 깨끗한 맥락(마지막 질문 + 답변)만 전송한다 — 본 대화 히스토리엔
     * assistant tool_calls·tool 결과가 섞여 있어, 그대로 tools:[] 로 재전송하면
     * 일부 게이트웨이가 "tool_calls 는 있는데 tools 정의가 없다"며 거부한다.
     */
    private List<String> suggestFollowups(List<LlmMessage> messages, String answer) {
        LlmMessage lastUser = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                lastUser = messages.get(i);
                break;
            }
        }
        try {
            LlmTurn turn = llm.next(List.of(
                    LlmMessage.of(Role.USER, lastUser != null && lastUser.content() != null
                            ? lastUser.content() : "이전 질문"),
                    LlmMessage.of(Role.ASSISTANT, answer),
                    LlmMessage.of(Role.USER, FOLLOWUP_PROMPT)), List.of());
            return turn instanceof LlmTurn.Final fin ? parseQuestionArray(fin.content()) : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 텍스트에서 첫 JSON 배열을 뽑아 문자열 3개까지. 실패 시 빈 배열. */
    static List<String> parseQuestionArray(String text) {
        Matcher m = JSON_ARRAY.matcher(text);
        if (!m.find()) {
            return List.of();
        }
        try {
            JsonNode arr = JSON.readTree(m.group());
            if (!arr.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonNode q : arr) {
                if (q.isTextual() && !q.asText().trim().isEmpty()) {
                    out.add(q.asText());
                    if (out.size() == 3) {
                        break;
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 폼 입력을 LLM 이 읽을 한 블록으로 요약. 아무것도 없으면 null(주입 안 함).
     * (미입력) 표기를 남겨 LLM 이 무엇을 되물을지 판단하게 한다.
     */
    static String formatFormContext(FormContext fc) {
        if (fc == null) {
            return null;
        }
        List<FormContext.ContextRow> rows = fc.context() != null ? fc.context() : List.of();
        List<String> equipments = rows.stream()
                .map(r -> r.equipment() != null ? r.equipment().trim() : "")
                .filter(v -> !v.isEmpty())
                .toList();
        // 폼의 센서 필드 값은 PARAM_INDEX(센서 파라미터 인덱스)로 취급 — 개발 편의.
        List<String> paramIndexes = rows.stream()
                .flatMap(r -> (r.chambers() != null ? r.chambers() : List.<FormContext.Chamber>of()).stream())
                .flatMap(c -> (c.sensors() != null ? c.sensors() : List.<FormContext.Sensor>of()).stream())
                .map(s -> s.name() != null ? s.name().trim() : "")
                .filter(v -> !v.isEmpty())
                .toList();
        String start = fc.timeRange() != null && fc.timeRange().start() != null
                ? fc.timeRange().start().trim() : "";
        String end = fc.timeRange() != null && fc.timeRange().end() != null
                ? fc.timeRange().end().trim() : "";

        if (equipments.isEmpty() && paramIndexes.isEmpty() && start.isEmpty() && end.isEmpty()) {
            return null;
        }
        return String.join("\n",
                "[분석 대상 — 사용자 폼 입력]",
                "- 설비: " + (equipments.isEmpty() ? "(미입력)" : String.join(", ", equipments)),
                "- PARAM_INDEX: " + (paramIndexes.isEmpty() ? "(미입력)" : String.join(", ", paramIndexes)),
                "- 기간: " + (start.isEmpty() ? "(미입력)" : start) + " ~ " + (end.isEmpty() ? "(미입력)" : end),
                "설비·PARAM_INDEX·기간이 모두 있으면 되묻지 말고 바로 fdc_trace_reading 으로 조회하라"
                        + "(PARAM_INDEX 는 센서 ID 가 아니라 param_index 인자로 그대로 넘긴다).",
                "(미입력)이 있을 때만 무엇을 더 입력해야 하는지 되물어라.");
    }

    /** 비지 않은 노트만 빈 줄로 이어 붙인다. 전부 비면 null(주입 안 함). */
    static String joinNotes(String... notes) {
        List<String> present = new ArrayList<>();
        for (String n : notes) {
            if (n != null && !n.isBlank()) {
                present.add(n);
            }
        }
        return present.isEmpty() ? null : String.join("\n\n", present);
    }

    /**
     * 사용자가 데이터 패널에서 붙여넣어 요청에 실은 스냅샷을 LLM 이 읽을 한 블록으로.
     * 아무것도 없으면 null(주입 안 함).
     *
     * <p>Design B: 행 있는(📌) 스냅샷은 {@link SnapshotDb}(임시 SQLite)로 적재되므로
     * 여기엔 <b>스키마만</b> 편다 — 행은 프롬프트에 붓지 않고 query_snapshot 툴로 조회하게
     * 한다(토큰 절약·대용량 정밀 조회). 카탈로그 항목(rows 없음)은 "이런 표가 있다"만
     * 알린다 — 내용이 아직 안 왔으니 지어내지 말고 필요하면 사용자에게 요청하라는 신호다.
     */
    static String formatDataSnapshots(List<ChatDataSnapshot> snapshots, SnapshotDb snapshotDb) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add("[제공된 데이터 — 사용자 첨부]");
        // 행 있는 스냅샷: 조회용 임시 DB로 적재됨 — 스키마만 주입하고 query_snapshot 으로 조회.
        if (snapshotDb != null && !snapshotDb.isEmpty()) {
            parts.add("아래 표는 조회용 임시 DB(SQLite)로 적재되어 있다. 값이 필요하면 "
                    + "query_snapshot 툴에 SELECT 문을 주어 조회하라(행 데이터는 여기 없다).");
            parts.add(snapshotDb.schemaCatalog());
        }
        // 행 없는 카탈로그 항목: "이런 표가 있다"만 알린다(내용은 아직 안 옴).
        List<String> catalogOnly = new ArrayList<>();
        for (ChatDataSnapshot s : snapshots) {
            if (s == null || (s.rows() != null && !s.rows().isEmpty())) {
                continue;
            }
            String label = s.label() != null && !s.label().isBlank() ? s.label() : s.queryKey();
            List<String> cols = s.columns() != null ? s.columns() : List.of();
            catalogOnly.add("- " + label + " (" + String.join(", ", cols)
                    + ") — 내용 미첨부(필요하면 사용자에게 요청).");
        }
        if (!catalogOnly.isEmpty()) {
            parts.add("아직 내용이 안 온 항목:");
            parts.addAll(catalogOnly);
        }
        // 헤더만 남았으면(담을 표도 카탈로그도 없음) 주입하지 않는다.
        return parts.size() > 1 ? String.join("\n", parts) : null;
    }

    /**
     * query_snapshot 툴 — 붙여넣어 임시 DB(SQLite)로 적재된 표를 SELECT 로 조회한다.
     * 실행 결과 표는 done 페이로드에 실리고, 요약이 LLM 에 되먹인다(일반 조회 툴과 동일
     * 경로). SELECT-only — 변경 문은 {@link SnapshotDb} 가 거부한다.
     */
    private static AgentTool querySnapshotTool(SnapshotDb snapshotDb) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sql", Map.of("type", "string", "description",
                "실행할 SELECT 문. 사용 가능한 테이블·컬럼은 [제공된 데이터]에 있다. "
                        + "SELECT/WITH 로 시작하는 단일 문장만 허용된다."));
        props.put("title", Map.of("type", "string", "description",
                "결과 표에 붙일 제목(선택)."));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("sql"));
        return new AgentTool(QUERY_SNAPSHOT_TOOL,
                "사용자가 붙여넣어 임시 DB(SQLite)로 적재된 표를 SELECT 로 조회한다. 값을 지어내지 말고 "
                        + "이 툴로 확인하라. INSERT/UPDATE/DROP 등 변경은 불가(SELECT 만).",
                params,
                args -> runSnapshotQuery(snapshotDb, args));
    }

    /** query_snapshot 실행 — SELECT 결과 표 + 요약. 가드 위반·SQL 오류는 사유를 되먹인다. */
    private static ToolResult runSnapshotQuery(SnapshotDb snapshotDb, Map<String, Object> args) {
        String sql = args != null ? asTrimmed(args.get("sql")) : null;
        String title = args != null ? asTrimmed(args.get("title")) : null;
        if (sql == null) {
            return ToolResult.of("sql 인자가 필요합니다(조회할 SELECT 문).");
        }
        try {
            ChatTable table = snapshotDb.query(sql, title);
            String cols = table.columns() != null ? String.join(", ", table.columns()) : "";
            return new ToolResult(
                    "query_snapshot: " + table.rows().size() + "행 조회됨 (컬럼: " + cols + ").",
                    List.of(table));
        } catch (IllegalArgumentException e) {
            return ToolResult.of("조회할 수 없습니다: " + e.getMessage()
                    + " SELECT 문만 허용되며, 사용 가능한 테이블·컬럼은 [제공된 데이터]에 있습니다.");
        }
    }
}

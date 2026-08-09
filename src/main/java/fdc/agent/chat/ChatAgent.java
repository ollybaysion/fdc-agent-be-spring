package fdc.agent.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.AgentTool.ToolResult;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.ChatImage;
import fdc.agent.contract.ChatLink;
import fdc.agent.contract.ChatTable;
import fdc.agent.contract.ChoiceRequest;
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
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillQuery;
import fdc.agent.skills.SkillRegistry;
import fdc.agent.skills.SkillSource;
import fdc.agent.skills.SkillSpec;
import fdc.agent.util.Trace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 에이전트 루프 — LLM 이 툴을 부르면 실행해 결과를 되먹이고, 최종 답이 나오면
 * 텍스트 + 수집한 표를 돌려준다. LLM(mock/openai)·데이터(fixture/oracle)·스킬 출처
 * (번들/akg)는 seam 뒤라 이 루프는 무지하다.
 *
 * <p>이 클래스가 아는 것은 <b>루프</b>뿐이다. 프롬프트에 무슨 말을 적을지는
 * {@link ChatPrompt} 가, 툴이 무엇을 하고 언제 쓰이는지는 각 {@link AgentTool} 이
 * 안다. 루프는 조회 툴과 수집 툴을 구분하지 않고 이름으로 찾아 실행할 뿐이다.
 */
public class ChatAgent {

    private static final int MAX_STEPS = 4;

    private static final String FOLLOWUP_PROMPT =
            "위 답변에 이어 사용자가 이 설비 분석 맥락에서 물을 만한 후속 질문 3개를 한국어로 제안하라. "
                    + "JSON 문자열 배열로만, 다른 설명 없이. 예: [\"...\", \"...\", \"...\"]";

    /** 스텝 한도까지 갔을 때의 답 — 툴 요약 원문(내부 지시문 포함) 대신 나간다. */
    private static final String OUT_OF_STEPS =
            "확인할 게 많아 한 번에 마무리하지 못했습니다. 질문을 조금 더 좁혀서 다시 물어봐 주세요.";

    private static final Pattern JSON_ARRAY = Pattern.compile("\\[[\\s\\S]*\\]");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 대화 히스토리 한 줄(FE 계약 느슨하게 수용). */
    public record HistoryMessage(Role role, String content) {

        /**
         * 모르는 role(FE 의 {@code "error"} 등)은 400 대신 {@code role=null} 로 받아
         * 컨트롤러가 드롭한다(#38 T11) — 자동 호출 경로에서 메시지 하나가 대화
         * 전체를 무음 고장내지 않게.
         */
        @JsonCreator
        public static HistoryMessage fromJson(
                @JsonProperty("role") String role, @JsonProperty("content") String content) {
            try {
                return new HistoryMessage(Role.from(role), content);
            } catch (IllegalArgumentException unknownRole) {
                return new HistoryMessage(null, content);
            }
        }
    }

    /**
     * finishReason: "stop" | "length". recommendQuestion 은 실패/미지원 시 빈 배열.
     * dataRequests 는 이 응답에서 조달을 요청한 데이터(없으면 빈 배열).
     * inputRequests 는 이 응답에서 입력 카드로 요청한 스칼라 값(없으면 빈 배열).
     * choiceRequests 는 이 응답에서 선택 카드로 제시한 선택지(없으면 빈 배열).
     */
    public record AgentResult(
            String text, List<ChatTable> tables, FinishReason finishReason,
            List<String> recommendQuestion, List<DataRequest> dataRequests,
            List<InputRequest> inputRequests, List<ChoiceRequest> choiceRequests,
            List<ChatImage> images, List<ChatLink> links) {

        /** 그림·링크를 내놓는 툴이 아직 없는 경로용 — 나머지는 그대로. */
        public AgentResult(
                String text, List<ChatTable> tables, FinishReason finishReason,
                List<String> recommendQuestion, List<DataRequest> dataRequests,
                List<InputRequest> inputRequests, List<ChoiceRequest> choiceRequests) {
            this(text, tables, finishReason, recommendQuestion, dataRequests,
                    inputRequests, choiceRequests, List.of(), List.of());
        }
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

    public AgentResult run(List<HistoryMessage> history) {
        return run(history, null, null);
    }

    public AgentResult run(List<HistoryMessage> history, List<ChatDataSnapshot> dataSnapshots) {
        return run(history, dataSnapshots, null);
    }

    /**
     * @param inputs 사용자가 입력 카드로 채워 되보낸 스칼라 값 — 스킬로 네임스페이스된
     *     {@code {skill: {key: value}}}. 프롬프트에 주입되고, 같은 (skill,key) 는
     *     {@code request_input} 재요청이 억제된다(왕복 종료 보장). 없으면 null.
     */
    public AgentResult run(
            List<HistoryMessage> history, List<ChatDataSnapshot> dataSnapshots,
            Map<String, Map<String, String>> inputs) {
        return run(history, dataSnapshots, inputs, null);
    }

    /**
     * @param scope 사용자가 질의 대상 트레이에 담은 것 — 이 질문이 무엇을 놓고 하는
     *     질문인지. 담긴 분석의 조회 키도 여기 실려 오고, 그 (skill,key) 역시
     *     {@code request_input} 재요청이 억제된다. 담긴 게 없으면 null.
     */
    public AgentResult run(
            List<HistoryMessage> history, List<ChatDataSnapshot> dataSnapshots,
            Map<String, Map<String, String>> inputs, QueryScope scope) {
        // 붙여넣은 스냅샷(행 있음) → 요청 단위 인메모리 SQLite(Design B). 없으면 null.
        // 요청이 끝나면 연결을 닫는다(finally) — 여러 반환점을 감싸려 루프를 분리한다.
        SnapshotDb snapshotDb = SnapshotDb.build(dataSnapshots);
        try {
            return runLoop(history, dataSnapshots, snapshotDb, inputs, scope);
        } finally {
            if (snapshotDb != null) {
                snapshotDb.close();
            }
        }
    }

    private AgentResult runLoop(
            List<HistoryMessage> history,
            List<ChatDataSnapshot> dataSnapshots, SnapshotDb snapshotDb,
            Map<String, Map<String, String>> providedInputs, QueryScope scope) {
        // 이번 요청에 붙는 툴 = 도메인 스킬(자동 로드) + 수집 툴 + (붙여넣은 표가 있으면)
        // query_snapshot. 시스템 프롬프트의 사용 규칙도 이 목록에서 나오므로, 안 붙은
        // 툴의 규칙은 애초에 프롬프트에 실리지 않는다.
        //
        // 스킬 spec 은 두 번 쓰인다 — 조회 툴로 컴파일되고(실행), 요청 가능한 조회의
        // 풀이 된다(조달). 같은 목록 위에 두 갈래가 서므로 스킬을 등재하면 양쪽이
        // 함께 늘고, 등재되지 않은 조회는 어느 쪽으로도 나가지 않는다.
        List<SkillSpec> skillSpecs = skillSource.specs();
        QueryPool pool = QueryPool.of(skillSpecs);
        QueryProgress progress = QueryProgress.of(pool, dataSnapshots);

        // 풀이 비면 retrieve_data 를 아예 붙이지 않는다 — 요청할 수 있는 게 없는데 규칙만
        // 프롬프트에 남으면, 못 부를 툴을 쓰라고 지시하는 꼴이 된다.
        RetrieveDataTool dataRequests = pool.isEmpty() ? null : new RetrieveDataTool(pool, progress);
        InputRequestTool inputRequests = new InputRequestTool(providedInputs, scope);
        ChoiceRequestTool choiceRequests = new ChoiceRequestTool();

        List<AgentTool> tools = new ArrayList<>(SkillRegistry.compile(skillSpecs, skillQuery));
        if (snapshotDb != null) {
            tools.add(new SnapshotQueryTool(snapshotDb));
        }
        if (dataRequests != null) {
            tools.add(dataRequests);
        }
        tools.add(inputRequests);
        tools.add(choiceRequests);

        Map<String, AgentTool> toolByName = new LinkedHashMap<>();
        tools.forEach(t -> toolByName.put(t.name(), t));
        List<LlmToolSpec> specs = tools.stream()
                .map(t -> new LlmToolSpec(t.name(), t.description(), t.parameters()))
                .toList();

        // 이번 요청에서 LLM 이 실제로 보는 툴 전량(이름·설명·파라미터 스키마).
        // query_snapshot 은 붙여넣은 표가 있을 때만 여기 있다 — 없으면 모델이 안 부른 게
        // 아니라 애초에 부를 수 없었던 것이고, 그 구분이 진단의 절반이다.
        Trace.emit("BE→LLM 툴 노출 (" + specs.size() + "개)", specs);

        List<LlmMessage> messages = ChatPrompt.messages(
                ChatPrompt.system(tools),
                history,
                ChatPrompt.contextSection(
                        scope, dataSnapshots, snapshotDb, progress, providedInputs));

        List<ChatTable> tables = new ArrayList<>();

        for (int step = 0; step < MAX_STEPS; step++) {
            Trace.emit("BE→LLM step " + (step + 1) + " 메시지 (" + messages.size() + "개)", messages);
            LlmTurn turn = llm.next(messages, specs);
            Trace.emit("LLM→BE step " + (step + 1) + " 응답", turn);
            if (turn instanceof LlmTurn.Final fin) {
                return new AgentResult(fin.content(), tables, FinishReason.STOP,
                        suggestFollowups(messages, fin.content()),
                        collectedRequests(dataRequests), inputRequests.collected(),
                        choiceRequests.collected());
            }

            List<LlmToolCall> toolCalls = ((LlmTurn.ToolCalls) turn).toolCalls();
            messages.add(LlmMessage.assistantToolCalls(toolCalls));
            for (LlmToolCall call : toolCalls) {
                AgentTool tool = toolByName.get(call.name());
                ToolResult result = tool != null
                        ? tool.run(call.arguments())
                        : ToolResult.of("알 수 없는 툴: " + call.name());
                traceToolCall(call, result);
                if (result.tables() != null) {
                    tables.addAll(result.tables());
                }
                messages.add(LlmMessage.toolResult(call.id(), call.name(), result.summary()));
            }
        }

        // 스텝 한도 초과. 마지막 툴 요약을 그대로 돌려주면 스킬의 [출력 지침]·[하지 말 것]
        // 같은 내부 지시문이 화면에 그대로 나가므로, 모아 둔 표·카드만 들려 보낸다.
        return new AgentResult(OUT_OF_STEPS, tables, FinishReason.LENGTH, List.of(),
                collectedRequests(dataRequests), inputRequests.collected(), choiceRequests.collected());
    }

    /** 조달 요청 — 풀이 비어 툴이 안 붙은 요청에서는 애초에 모일 것이 없다. */
    private static List<DataRequest> collectedRequests(RetrieveDataTool tool) {
        return tool != null ? tool.collected() : List.of();
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
        List<LlmMessage> followupMessages = List.of(
                LlmMessage.of(Role.USER, lastUser != null && lastUser.content() != null
                        ? lastUser.content() : "이전 질문"),
                LlmMessage.of(Role.ASSISTANT, answer),
                LlmMessage.of(Role.USER, FOLLOWUP_PROMPT));
        try {
            Trace.emit("BE→LLM 후속 질문 요청 (툴 없음)", followupMessages);
            LlmTurn turn = llm.next(followupMessages, List.of());
            Trace.emit("LLM→BE 후속 질문 응답", turn);
            return turn instanceof LlmTurn.Final fin ? parseQuestionArray(fin.content()) : List.of();
        } catch (RuntimeException e) {
            Trace.raw("후속 질문 실패 (답변은 이미 확정 — 빈 배열로 진행)", String.valueOf(e));
            return List.of();
        }
    }

    /**
     * 툴 한 번의 왕복을 트레이스에 — LLM 이 채워 준 인자, 되먹인 요약, 딸려 나온 표.
     * 표는 제목·컬럼·행수만 남긴다(값 전량은 done 페이로드 트레이스에서 본다).
     * 수집 툴(retrieve_data·request_input)도 같은 경로라 자동으로 함께 찍힌다.
     */
    private static void traceToolCall(LlmToolCall call, ToolResult result) {
        if (!Trace.on()) {
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", call.name());
        out.put("arguments", call.arguments());
        out.put("resultSummary", result.summary());
        if (result.tables() != null) {
            List<Map<String, Object>> shapes = new ArrayList<>();
            for (ChatTable t : result.tables()) {
                Map<String, Object> shape = new LinkedHashMap<>();
                shape.put("title", t.title());
                shape.put("columns", t.columns());
                shape.put("rowCount", t.rows() != null ? t.rows().size() : 0);
                shapes.add(shape);
            }
            out.put("tables", shapes);
        }
        Trace.emit("툴 실행 " + call.name(), out);
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
}

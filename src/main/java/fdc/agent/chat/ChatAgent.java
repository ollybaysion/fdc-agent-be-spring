package fdc.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.AgentTool.ToolResult;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.ChatTable;
import fdc.agent.data.EquipmentRepo;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmToolSpec;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.skills.SkillQuery;
import fdc.agent.skills.SkillRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 에이전트 루프(Node 판 chat/agent.ts 대응). LLM 이 툴을 부르면 repo 로
 * 실행해 결과를 되먹이고, 최종 답이 나오면 텍스트 + 수집한 표를 돌려준다.
 * LLM(mock/openai)·데이터(fixture/oracle)는 seam 뒤라 이 루프는 무지.
 *
 * 툴 = 설비 조회(손툴) + 도메인 스킬(spec 컴파일). FE "설비 정보 입력" 폼
 * (설비·센서·기간)은 FormContext 로 들어와 프롬프트에 주입된다.
 */
public class ChatAgent {

    private static final int MAX_STEPS = 4;

    /** 붙여넣은 스냅샷 한 건에서 프롬프트에 펴는 최대 행수(큰 표가 프롬프트를 삼키지 않게). */
    private static final int MAX_SNAPSHOT_ROWS = 200;

    private static final String SYSTEM_PROMPT = String.join(" ",
            "당신은 반도체 설비 이상탐지(FDC) 분석 어시스턴트다.",
            "설비/챔버/센서 데이터가 필요하면 반드시 제공된 툴을 호출해 사실을 확인하고,",
            "조회하지 않은 값은 지어내지 않는다.",
            "센서 데이터 분석에는 설비·PARAM_INDEX·기간이 모두 필요하다 — 폼 입력이나 대화에서",
            "이 중 빠진 게 있으면 추측하지 말고 무엇을 더 입력해야 하는지 사용자에게 되물어라.",
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
    public record HistoryMessage(String role, String content) {
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

    /** finishReason: "stop" | "length". recommendQuestion 은 실패/미지원 시 빈 배열. */
    public record AgentResult(
            String text, List<ChatTable> tables, String finishReason, List<String> recommendQuestion) {
    }

    private final LlmClient llm;
    private final EquipmentRepo repo;
    private final SkillQuery skillQuery;

    public ChatAgent(LlmClient llm, EquipmentRepo repo, SkillQuery skillQuery) {
        this.llm = llm;
        this.repo = repo;
        this.skillQuery = skillQuery;
    }

    public AgentResult run(List<HistoryMessage> history, FormContext formContext) {
        return run(history, formContext, null);
    }

    public AgentResult run(
            List<HistoryMessage> history, FormContext formContext, List<ChatDataSnapshot> dataSnapshots) {
        // 설비 조회 툴 + 도메인 스킬 툴(explain-sensor / trace-reading 등 자동 로드).
        List<AgentTool> tools = new ArrayList<>();
        tools.addAll(EquipmentTools.buildEquipmentTools(repo));
        tools.addAll(SkillRegistry.buildSkillTools(skillQuery));
        Map<String, AgentTool> toolByName = new LinkedHashMap<>();
        tools.forEach(t -> toolByName.put(t.name(), t));
        List<LlmToolSpec> specs = tools.stream()
                .map(t -> new LlmToolSpec(t.name(), t.description(), t.parameters()))
                .toList();

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.of("system", SYSTEM_PROMPT));
        List<LlmMessage> hist = new ArrayList<>(history.stream()
                .map(m -> LlmMessage.of(
                        "assistant".equals(m.role()) ? "assistant" : "user",
                        m.content() != null ? m.content() : ""))
                .toList());
        // 폼 컨텍스트 + 사용자가 붙여넣은 데이터 스냅샷을 별도 system 메시지가 아니라
        // 마지막 사용자 메시지에 덧붙인다 — 약한 모델도 요청의 일부로 확실히 반영
        // (값이 다 있으면 바로 분석, 붙여넣은 데이터가 있으면 그걸 근거로).
        String note = joinNotes(formatFormContext(formContext), formatDataSnapshots(dataSnapshots));
        if (note != null) {
            int lastUser = -1;
            for (int i = hist.size() - 1; i >= 0; i--) {
                if ("user".equals(hist.get(i).role())) {
                    lastUser = i;
                    break;
                }
            }
            if (lastUser >= 0) {
                LlmMessage m = hist.get(lastUser);
                hist.set(lastUser, LlmMessage.of(m.role(),
                        (m.content() != null ? m.content() : "") + "\n\n" + note));
            } else {
                hist.add(LlmMessage.of("user", note));
            }
        }
        messages.addAll(hist);

        List<ChatTable> tables = new ArrayList<>();

        for (int step = 0; step < MAX_STEPS; step++) {
            LlmTurn turn = llm.next(messages, specs);
            if (turn instanceof LlmTurn.Final fin) {
                List<String> recommendQuestion = suggestFollowups(messages, fin.content());
                return new AgentResult(fin.content(), tables, "stop", recommendQuestion);
            }

            List<LlmToolCall> toolCalls = ((LlmTurn.ToolCalls) turn).toolCalls();
            messages.add(LlmMessage.assistantToolCalls(toolCalls));
            for (LlmToolCall call : toolCalls) {
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
            if ("tool".equals(messages.get(i).role())) {
                lastTool = messages.get(i).content();
                break;
            }
        }
        return new AgentResult(
                lastTool != null ? lastTool : "요청을 완료하지 못했습니다. 좀 더 구체적으로 질문해 주세요.",
                tables, "length", List.of());
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
            if ("user".equals(messages.get(i).role())) {
                lastUser = messages.get(i);
                break;
            }
        }
        try {
            LlmTurn turn = llm.next(List.of(
                    LlmMessage.of("user", lastUser != null && lastUser.content() != null
                            ? lastUser.content() : "이전 질문"),
                    LlmMessage.of("assistant", answer),
                    LlmMessage.of("user", FOLLOWUP_PROMPT)), List.of());
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
     * <p>📌(rows 있음)은 전문을 표로 펴서 주입해 LLM 이 값을 직접 근거로 삼게 하고,
     * 카탈로그 항목(rows 없음)은 "이런 표가 있다"만 알린다 — 내용이 아직 안 왔으므로
     * 지어내지 말고 필요하면 사용자에게 요청하라는 신호다. 큰 표는 행수를 캡한다.
     */
    static String formatDataSnapshots(List<ChatDataSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add("[제공된 데이터 — 사용자 첨부]");
        parts.add("아래는 사용자가 직접 조회해 붙여넣은 데이터다. 이 값을 근거로 답하고, "
                + "여기 없는 값은 지어내지 않는다.");
        for (ChatDataSnapshot s : snapshots) {
            String label = s.label() != null && !s.label().isBlank() ? s.label() : s.queryKey();
            List<String> cols = s.columns() != null ? s.columns() : List.of();
            List<List<String>> rows = s.rows();
            int rowCount = s.rowCount() != null ? s.rowCount() : (rows != null ? rows.size() : 0);
            String head = "- " + label + " (" + String.join(", ", cols) + "), " + rowCount + "행";
            if (rows == null || rows.isEmpty()) {
                parts.add(head + " — 내용 미첨부(필요하면 사용자에게 요청).");
                continue;
            }
            parts.add(head + ":");
            parts.add("  " + String.join(" | ", cols));
            int shown = Math.min(rows.size(), MAX_SNAPSHOT_ROWS);
            for (int i = 0; i < shown; i++) {
                List<String> cells = new ArrayList<>();
                for (String c : rows.get(i)) {
                    cells.add(c == null ? "" : c);
                }
                parts.add("  " + String.join(" | ", cells));
            }
            if (rows.size() > shown) {
                parts.add("  … (" + rows.size() + "행 중 처음 " + shown + "행만 표시)");
            }
        }
        return String.join("\n", parts);
    }
}

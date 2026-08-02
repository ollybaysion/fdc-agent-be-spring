package fdc.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 분기 판정 호출(#55)의 프롬프트 합성과 응답 파싱 — 문안 정본은
 * {@code docs/branch-prompt.md} 다(2026-08-03 확정).
 *
 * <p>spec {@code branches.when} 은 문법 없는 산문이라 기계 평가하지 않는다 — 조건
 * 원문과 도착한 표를 그대로 보여 주고 성립 여부만 JSON 한 줄로 받는다. 메시지는
 * <b>판정 본문 user 하나뿐</b>: 대화 이력을 싣지 않는다(판정 근거는 표의 값뿐이라
 * 질문이 판정에 영향을 주면 안 된다). system·tools 도 없다.
 *
 * <p>파싱은 보수 강등이 규율이다 — JSON 이 아니거나, decision 이 낯설거나, index 가
 * 범위 밖이거나, decision 종류가 spec 의 분기 효과와 안 맞으면 전부 null(=continue).
 * 잘못돼 봐야 분기 기능이 없던 동작으로 떨어진다.
 */
public final class BranchPrompt {
    private BranchPrompt() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 판정 본문 머리 헤딩 — 목({@code MockLlm})과 테스트가 문장이 아니라 여기 붙는다. */
    public static final String SECTION_HEAD = "# 조회 절차 분기 판단";

    /** 프롬프트 표에 싣는 행 수 상한 — 분기 근거가 이 위로 필요할 일은 없다. */
    private static final int MAX_ROWS = 20;

    /** 성립 판정 하나 — {@code decision} 은 "stop" | "open" (continue 는 null 반환). */
    public record Call(String decision, int index, String reason) {
    }

    /** 판정 본문 하나가 메시지의 전부다. */
    public static List<LlmMessage> messages(String body) {
        return List.of(LlmMessage.of(Role.USER, body));
    }

    /**
     * 판정 본문 — 전부 spec·도착 실물에서 결정론 조립. {@code steps} 는 열림형 분기의
     * 대상 스텝 제목을 찾는 데 쓴다.
     */
    public static String body(
            SkillSpec spec, String skill, Map<String, String> args,
            QueryPool.Query query, List<QueryPool.Query> steps, ChatDataSnapshot data) {
        String focus = spec != null && spec.focus() != null && !spec.focus().isBlank()
                ? spec.focus().trim()
                : skill;
        String stepName = (query.step() + 1) + "단계 \"" + NarrationPrompt.stepLabel(query.title()) + "\"";

        List<String> parts = new ArrayList<>();
        parts.add(SECTION_HEAD + "\n\n"
                + "절차를 진행하다 분기 조건이 있는 단계의 데이터가 도착했다. 아래 결과를 보고\n"
                + "성립하는 분기가 있으면 그 효과대로 가고, 없으면 다음 단계로 계속한다.");

        List<String> proc = new ArrayList<>();
        proc.add("## 절차");
        proc.add("");
        proc.add("- 스킬: " + skill + " (" + focus + ")");
        proc.add("- 시작 인자: " + argsLine(args));
        proc.add("- 도착한 단계: " + stepName);
        parts.add(String.join("\n", proc));

        parts.add("## " + (query.step() + 1) + "단계 결과\n\n" + table(data));

        List<String> branches = new ArrayList<>();
        branches.add("## 분기 목록 (0부터 번호)");
        branches.add("");
        List<SkillSpec.SkillBranch> list = query.branches();
        for (int i = 0; i < list.size(); i++) {
            branches.add(i + ". 조건: " + list.get(i).when() + " → 효과: " + effect(list.get(i), steps));
        }
        parts.add(String.join("\n", branches));

        parts.add("## 지시\n\n"
                + "위 표를 근거로 각 조건의 성립 여부를 판단하라. 반드시 아래 JSON 한 줄로만 답하라\n"
                + "(다른 문장·설명·코드펜스 금지):\n\n"
                + "- 성립한 분기의 효과가 종료다: {\"decision\":\"stop\",\"index\":<번호>,\"reason\":\"<근거 한 줄>\"}\n"
                + "- 성립한 분기의 효과가 단계 열림이다: {\"decision\":\"open\",\"index\":<번호>,\"reason\":\"<근거 한 줄>\"}\n"
                + "- 성립하는 분기가 없다(또는 애매하다): {\"decision\":\"continue\"}");

        parts.add("## 규칙\n\n"
                + "- 조건 문장은 정해진 문법 없이 사람 말로 적혀 있다 — 문구 형태가 아니라 뜻으로 평가한다.\n"
                + "- 성립 근거는 위 표의 값뿐이다 — 표에 없는 값을 가정하지 않는다.\n"
                + "- decision 종류는 성립한 분기에 적힌 효과를 그대로 따른다 — 종료면 stop, 열림이면 open.\n"
                + "- 조금이라도 애매하면 continue 다. 분기는 확실할 때만 탄다.\n"
                + "- 여러 분기가 성립하면 번호가 빠른 것 하나만.\n"
                + "- reason 은 화면에 그대로 표시된다 — 한국어 한 문장, 존댓말.");

        return String.join("\n\n", parts);
    }

    /** 인자 순서는 키 정렬 — 같은 run 은 같은 문장(순수 함수 전제, NarrationPrompt 동일). */
    private static String argsLine(Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return "(없음)";
        }
        List<String> kv = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(args).entrySet()) {
            kv.add(e.getKey() + "=" + e.getValue());
        }
        return String.join(", ", kv);
    }

    /** 분기 효과 한 마디 — 열림형은 대상 스텝 제목까지, 종료형은 then 산문 그대로. */
    private static String effect(SkillSpec.SkillBranch branch, List<QueryPool.Query> steps) {
        if (branch.opens() == null) {
            return "종료 — " + (branch.then() != null ? branch.then() : "절차를 종료한다");
        }
        String title = branch.opens() >= 0 && branch.opens() < steps.size()
                ? NarrationPrompt.stepLabel(steps.get(branch.opens()).title())
                : "?";
        return "열림 — " + (branch.opens() + 1) + "단계 \"" + title + "\" 를 연다"
                + (branch.then() != null ? " (" + branch.then() + ")" : "");
    }

    /** 도착 표 → markdown 표. 셀의 파이프는 표를 깨므로 슬래시로 바꾼다. */
    private static String table(ChatDataSnapshot data) {
        List<String> columns = data.columns() != null ? data.columns() : List.of();
        if (columns.isEmpty()) {
            return "(컬럼 정보 없음)";
        }
        List<String> lines = new ArrayList<>();
        lines.add("| " + String.join(" | ", columns.stream().map(BranchPrompt::cell).toList()) + " |");
        lines.add("| " + String.join(" | ", columns.stream().map(c -> "---").toList()) + " |");
        List<List<String>> rows = data.rows() != null ? data.rows() : List.of();
        List<List<String>> shown = rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;
        for (List<String> row : shown) {
            lines.add("| " + String.join(" | ", row.stream().map(BranchPrompt::cell).toList()) + " |");
        }
        if (rows.size() > shown.size()) {
            lines.add("");
            lines.add("(외 " + (rows.size() - shown.size()) + "행 생략)");
        }
        return String.join("\n", lines);
    }

    private static String cell(String value) {
        return value == null ? "(null)" : value.replace("|", "/");
    }

    /**
     * 응답 파싱 — 성립(stop/open)만 {@link Call} 로, 나머지는 전부 null(=continue).
     * 모델이 코드펜스로 감싸는 흔한 위반은 첫 {@code {} 블록 추출로 흡수한다.
     */
    public static Call parse(String content, List<SkillSpec.SkillBranch> branches) {
        if (content == null) {
            return null;
        }
        int open = content.indexOf('{');
        int close = content.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(content.substring(open, close + 1));
        } catch (Exception bad) {
            return null;
        }
        String decision = root.path("decision").asText(null);
        if (!"stop".equals(decision) && !"open".equals(decision)) {
            return null;
        }
        int index = root.path("index").asInt(-1);
        if (index < 0 || index >= branches.size()) {
            return null;
        }
        boolean opensStep = branches.get(index).opens() != null;
        if (opensStep != "open".equals(decision)) {
            return null; // spec 효과와 어긋난 종류 — 지어낸 판정은 받지 않는다.
        }
        String reason = root.path("reason").asText("");
        return new Call(decision, index, reason);
    }
}

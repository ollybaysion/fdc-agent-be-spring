package fdc.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.RunProgress;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.contract.Role;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillSpec;
import fdc.agent.util.Trace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채움 폭포의 <b>3차</b> — 결정론으로 못 이은 사실을 LLM 이 잇는다.
 *
 * <p>1차(정확 일치)와 2차(같은 컬럼명 + run 대조)는 이름이 맞아야 닿는다. 사람이 사내
 * 리포트를 그대로 붙여넣으면 컬럼 이름이 우리 spec 과 다르고, 그러면 답이 이미 화면에
 * 있는데도 판정은 "모른다"고 말한다. 그 자리를 메우는 층이다.
 *
 * <p><b>부를 조건이 좁다</b>: 못 채운 need 가 남아 있고, <b>동시에</b> 결정론이 쓰지
 * 못한 표가 실제로 있을 때만. 둘 중 하나라도 없으면 물어볼 것이 없으므로 호출하지
 * 않는다 — 판정 왕복마다 LLM 이 도는 설계가 아니다.
 *
 * <p><b>값은 표에 실제로 있어야 받는다.</b> 모델이 고른 값이 그 스냅샷의 그 컬럼에
 * 없으면 버린다. 여기서 나온 값은 확인 절차 없이 다음 조회의 바인드로 들어가므로
 * (사용자 결정), 창작을 막는 것은 이 결정론 검증뿐이다. 대신 어디서 왔는지는
 * {@link RunProgress.Need#source} 에 남는다.
 *
 * <p><b>이미지는 아직 이 층에 없다.</b> 전용 vision 모델로 보낸다는 결정은 서 있지만
 * 요청 body 에 이미지 입력 채널이 없어 지금 볼 수 있는 것은 텍스트 표뿐이다. 채널이
 * 생기면 {@link #ask} 의 {@code llm} 인자가 갈라지는 자리다 — 판정 규칙과 검증은 그대로 두고
 * 모델만 바꾼다.
 */
public final class AltFillJudge {
    private AltFillJudge() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");

    /** 프롬프트에 실을 표 수·행 수 상한 — 근거로 충분하되 맥락을 삼키지 않게. */
    private static final int MAX_TABLES = 5;
    private static final int MAX_ROWS = 5;

    private static final String IDENTITY = String.join(" ",
            "당신은 데이터 대조기다.",
            "주어진 표에서 요구된 사실을 찾아 연결하는 일만 한다.",
            "표에 없는 값은 절대 만들지 않는다 — 못 찾으면 그 항목을 빼면 된다.");

    /** 채워진 사실 하나 — 무엇을, 어떤 값으로, 어느 표에서. */
    public record AltFill(String need, String value, String source) {
    }

    /**
     * 못 채운 need 를 등록된 다른 표에서 찾아본다.
     *
     * @return run 키({@link PanelJudge#runKey}) → 그 절차에서 새로 채워진 것들.
     *     물어볼 것이 없거나 LLM 이 실패하면 빈 맵(판정은 결정론 결과 그대로 간다).
     */
    public static Map<String, List<AltFill>> ask(
            LlmClient llm, QueryPool pool, PanelJudge.PanelBody body,
            List<RunProgress> runsProgress) {
        List<ChatDataSnapshot> unmatched = unmatched(pool, body);
        if (unmatched.isEmpty()) {
            return Map.of();
        }
        Map<String, List<AltFill>> out = new LinkedHashMap<>();
        for (RunProgress run : runsProgress) {
            List<RunProgress.Need> unmet = unmet(run);
            if (unmet.isEmpty() || !pool.knows(run.skill())) {
                continue;
            }
            List<AltFill> fills = askRun(llm, pool, run, unmet, unmatched);
            if (!fills.isEmpty()) {
                out.put(PanelJudge.runKey(run.skill(), run.args(), pool), fills);
            }
        }
        return out;
    }

    private static List<RunProgress.Need> unmet(RunProgress run) {
        List<RunProgress.Need> out = new ArrayList<>();
        for (RunProgress.Need need : run.needs() != null ? run.needs() : List.<RunProgress.Need>of()) {
            if ("UNFILLED".equals(need.state()) || "UNPROCURABLE".equals(need.state())) {
                out.add(need);
            }
        }
        return out;
    }

    /**
     * 결정론이 쓰지 못한 표 — 풀 밖 키(자유 저작)이거나 다른 절차의 것. 정확 일치와
     * run 대조는 이미 {@link ArrivalLens} 가 훑었으므로 여기 남는 것이 진짜 미매칭이다.
     */
    private static List<ChatDataSnapshot> unmatched(QueryPool pool, PanelJudge.PanelBody body) {
        Set<String> seen = new LinkedHashSet<>();
        List<ChatDataSnapshot> out = new ArrayList<>();
        for (ChatDataSnapshot s : body.snapshots() != null
                ? body.snapshots() : List.<ChatDataSnapshot>of()) {
            if (s == null || !s.hasRows() || s.queryKey() == null || !seen.add(s.queryKey())) {
                continue;
            }
            QueryKey.Parsed parsed = QueryKey.parse(s.queryKey());
            if (parsed != null && pool.byId(parsed.queryId()) != null) {
                continue; // 풀 안의 키 — 1·2차가 볼 수 있었던 표다.
            }
            out.add(s);
            if (out.size() == MAX_TABLES) {
                break;
            }
        }
        return out;
    }

    private static List<AltFill> askRun(
            LlmClient llm, QueryPool pool, RunProgress run,
            List<RunProgress.Need> unmet, List<ChatDataSnapshot> tables) {
        List<LlmMessage> prompt = List.of(
                LlmMessage.of(Role.SYSTEM, IDENTITY),
                LlmMessage.of(Role.USER, question(pool, run, unmet, tables)));
        try {
            Trace.emit("BE→LLM 3차 채움 판정 요청 (툴 없음)", prompt);
            LlmTurn turn = llm.next(prompt, List.of());
            Trace.emit("LLM→BE 3차 채움 판정 응답", turn);
            if (!(turn instanceof LlmTurn.Final fin) || fin.content() == null) {
                return List.of();
            }
            return verified(parse(fin.content()), unmet, tables);
        } catch (RuntimeException e) {
            // 판정을 죽이지 않는다 — 3차가 실패하면 결정론 결과가 그대로 답이다.
            Trace.raw("3차 채움 판정 실패 (결정론 판정 그대로)", String.valueOf(e));
            return List.of();
        }
    }

    private static String question(
            QueryPool pool, RunProgress run,
            List<RunProgress.Need> unmet, List<ChatDataSnapshot> tables) {
        Map<String, String> expected = expectedColumns(pool, run.skill());
        List<String> lines = new ArrayList<>();
        lines.add("아래 절차에서 아직 알아내지 못한 것들이 있다: " + run.label());
        for (RunProgress.Need need : unmet) {
            String where = expected.get(need.id());
            lines.add("- " + need.id() + ": " + need.what()
                    + (where != null ? " (원래 자리: " + where + ")" : " (지정된 조회 없음)"));
        }
        lines.add("");
        lines.add("사용자가 등록한 표 중 위 조회로 요청하지 않은 것들이다:");
        for (ChatDataSnapshot table : tables) {
            lines.add("[" + table.queryKey() + "] " + (table.label() != null ? table.label() : ""));
            lines.add("  컬럼: " + String.join(", ",
                    table.columns() != null ? table.columns() : List.<String>of()));
            List<List<String>> rows = table.rows();
            for (int i = 0; i < Math.min(rows.size(), MAX_ROWS); i++) {
                lines.add("  " + String.join(" | ", nullSafe(rows.get(i))));
            }
            if (rows.size() > MAX_ROWS) {
                lines.add("  … 외 " + (rows.size() - MAX_ROWS) + "행");
            }
        }
        lines.add("");
        lines.add("각 항목의 값이 이 표들 안에 있으면 JSON 으로만 답하라:");
        lines.add("{\"fills\":[{\"need\":\"항목 id\",\"source\":\"표 이름\",\"column\":\"컬럼명\","
                + "\"value\":\"표에 있는 값 그대로\"}]}");
        lines.add("확신할 수 없는 항목은 빼라. 없으면 {\"fills\":[]} 로 답하라."
                + " 표에 없는 값을 쓰면 그 항목은 버려진다.");
        return String.join("\n", lines);
    }

    /** need id → {@code 조달id.COLUMN} 표기. 조달 수단이 없는 need 는 빠진다. */
    private static Map<String, String> expectedColumns(QueryPool pool, String skill) {
        Map<String, String> out = new LinkedHashMap<>();
        for (SkillSpec.SkillNeed need : pool.needsOf(skill)) {
            if (need == null || need.fills().isEmpty()) {
                continue;
            }
            SkillSpec.Fill fill = need.fills().get(0);
            out.put(need.id(), fill.query() + "." + fill.column());
        }
        return out;
    }

    private static List<AltFill> parse(String text) {
        Matcher m = JSON_OBJECT.matcher(text);
        if (!m.find()) {
            return List.of();
        }
        try {
            JsonNode fills = JSON.readTree(m.group()).path("fills");
            List<AltFill> out = new ArrayList<>();
            for (JsonNode fill : fills) {
                String need = fill.path("need").asText(null);
                String value = fill.path("value").asText(null);
                String source = fill.path("source").asText(null);
                if (need != null && value != null && !value.isBlank()) {
                    out.add(new AltFill(need.trim(), value.trim(),
                            source != null ? source.trim() : null));
                }
            }
            return out;
        } catch (Exception badJson) {
            return List.of();
        }
    }

    /**
     * 모델이 준 것 중 <b>실제로 그 표에 있는 값</b>만 남긴다. 지목한 need 가 물어본
     * 목록에 없거나, 값이 어느 표에도 없으면 버린다 — 이 층이 창작을 막는 유일한 자리다.
     */
    private static List<AltFill> verified(
            List<AltFill> claimed, List<RunProgress.Need> unmet, List<ChatDataSnapshot> tables) {
        Set<String> asked = new LinkedHashSet<>();
        for (RunProgress.Need need : unmet) {
            asked.add(need.id());
        }
        Map<String, ChatDataSnapshot> byKey = new LinkedHashMap<>();
        for (ChatDataSnapshot table : tables) {
            byKey.put(table.queryKey(), table);
        }
        List<AltFill> out = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        for (AltFill fill : claimed) {
            if (!asked.contains(fill.need()) || !taken.add(fill.need())) {
                continue;
            }
            ChatDataSnapshot table = byKey.get(fill.source());
            String source = table != null && contains(table, fill.value())
                    ? fill.source()
                    : anyContaining(tables, fill.value());
            if (source != null) {
                out.add(new AltFill(fill.need(), fill.value(), source));
            }
        }
        return out;
    }

    /** 표 이름을 잘못 짚었어도 값이 어딘가에 실제로 있으면 받는다 — 근거는 그 표다. */
    private static String anyContaining(List<ChatDataSnapshot> tables, String value) {
        for (ChatDataSnapshot table : tables) {
            if (contains(table, value)) {
                return table.queryKey();
            }
        }
        return null;
    }

    private static boolean contains(ChatDataSnapshot table, String value) {
        for (List<String> row : table.rows() != null ? table.rows() : List.<List<String>>of()) {
            for (String cell : row != null ? row : List.<String>of()) {
                if (cell != null && cell.trim().equalsIgnoreCase(value.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> nullSafe(List<String> row) {
        List<String> out = new ArrayList<>();
        for (String cell : row != null ? row : List.<String>of()) {
            out.add(cell != null ? cell : "");
        }
        return out;
    }
}

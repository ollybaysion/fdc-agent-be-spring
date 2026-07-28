package fdc.agent.chat;

import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.skills.QueryPool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 조회 절차가 어디까지 왔나 — <b>도착한 스냅샷에서 유도한다</b>.
 *
 * <p>진행을 어디에도 저장하지 않는 게 요점이다. {@link QueryKey} 가 한 절차의 모든 단계에
 * 같은 run 이름표를 달아 주므로, 이번 요청에 실려 온 스냅샷들의 키만 풀에 비추면
 * "fdc-explain-sensor(snsr_id=S-0004)는 1단계까지 왔고 다음은 2단계"가 나온다.
 * 계약에 {@code progress} 필드가 없어도 되고, 서버가 세션을 들고 있지 않아도 된다 —
 * <b>진행은 상태가 아니라 도착의 결과다.</b>
 *
 * <p>여기서 나온 한 걸음이 맥락 섹션에 실려 모델을 밀어 준다. 다만 밀 뿐이다:
 * 멈추는 판단(질문에 답하기 충분한가, 0행으로 끝났는가)은 모델이 한다.
 *
 * <p>풀 형식이 아닌 키(옛 자유 저작 스냅샷)는 조용히 빠진다 — 적재도 억제도 그대로 받되
 * 진행으로는 읽지 않는다.
 */
public final class QueryProgress {

    /** 한 단계의 도착 여부. {@code emptyResult} 는 도착했고 그 결과가 0행이라는 뜻이다. */
    public record Step(int index, String title, boolean arrived, boolean emptyResult) {
    }

    /** 한 절차의 진행 = (스킬, run 이름표) 하나. */
    public record Run(String skill, String argsPart, Map<String, String> args, List<Step> steps) {

        /** 아직 도착하지 않은 첫 단계. 전부 도착했으면 -1. */
        public int nextStep() {
            for (Step s : steps) {
                if (!s.arrived()) {
                    return s.index();
                }
            }
            return -1;
        }

        /** 0행으로 확인된 단계가 있으면 그 단계, 없으면 null — 절차는 거기서 끝난다. */
        public Step emptyAt() {
            for (Step s : steps) {
                if (s.emptyResult()) {
                    return s;
                }
            }
            return null;
        }
    }

    private final Map<String, ChatDataSnapshot> arrivedByKey;
    private final List<Run> runs;

    private QueryProgress(Map<String, ChatDataSnapshot> arrivedByKey, List<Run> runs) {
        this.arrivedByKey = arrivedByKey;
        this.runs = runs;
    }

    /** 절차 하나를 가리키는 이름 — (스킬, run 이름표). 묶는 기준이자 등장 순서다. */
    private record RunId(String skill, String argsPart) {
    }

    public static QueryProgress of(QueryPool pool, List<ChatDataSnapshot> snapshots) {
        Map<String, ChatDataSnapshot> arrived = new LinkedHashMap<>();
        // 등장 순서를 유지해 프롬프트가 요청마다 흔들리지 않게.
        Set<RunId> seen = new LinkedHashSet<>();

        for (ChatDataSnapshot s : snapshots != null ? snapshots : List.<ChatDataSnapshot>of()) {
            if (s == null || s.queryKey() == null || s.queryKey().isBlank() || !s.arrived()) {
                continue;
            }
            arrived.put(s.queryKey().trim(), s);
            QueryKey.Parsed parsed = QueryKey.parse(s.queryKey());
            QueryPool.Query query =
                    parsed == null ? null : pool.byId(parsed.skill() + "#" + parsed.step());
            if (query == null) {
                continue; // 풀 밖 키 — 억제엔 쓰이되 진행으로는 읽지 않는다.
            }
            // 스킬 이름은 풀의 것으로 쓴다 — 키의 표기가 흔들려도 단계 목록을 못 찾는 일이
            // 없게(byId 는 표기 차이를 흡수하지만 stepsOf 는 정식 이름만 안다).
            seen.add(new RunId(query.skill(), parsed.argsPart()));
        }

        List<Run> runs = new ArrayList<>();
        for (RunId id : seen) {
            List<Step> steps = new ArrayList<>();
            for (QueryPool.Query q : pool.stepsOf(id.skill())) {
                String key = id.argsPart().isEmpty()
                        ? q.queryId()
                        : q.queryId() + "__" + id.argsPart();
                ChatDataSnapshot hit = arrived.get(key);
                steps.add(new Step(q.step(), q.title(), hit != null,
                        hit != null && hit.isEmptyResult()));
            }
            if (!steps.isEmpty()) {
                runs.add(new Run(id.skill(), id.argsPart(),
                        QueryKey.parseArgs(id.argsPart()), List.copyOf(steps)));
            }
        }
        return new QueryProgress(Map.copyOf(arrived), List.copyOf(runs));
    }

    public List<Run> runs() {
        return runs;
    }

    /** 이 스킬로 진행 중인 절차들 — 인자를 빼먹은 이어가기 요청을 붙일 자리를 찾는 데 쓴다. */
    public List<Run> runsOf(String skill) {
        return runs.stream().filter(r -> r.skill().equals(skill)).toList();
    }

    /** 결과가 도착한 스냅샷(행 있음 또는 0행 확인). 안 왔으면 null. */
    public ChatDataSnapshot arrived(String queryKey) {
        return queryKey == null ? null : arrivedByKey.get(queryKey.trim());
    }

    /**
     * 스냅샷의 한 컬럼에 실제로 있는 값들(중복·공백 제거, 등장 순서). 컬럼 이름은
     * 대소문자를 가리지 않는다 — spec 의 {@code column} 은 대문자, 붙여넣은 헤더는
     * 사용자가 복사한 그대로일 수 있다.
     */
    public static List<String> valuesOf(ChatDataSnapshot snapshot, String column) {
        if (snapshot == null || column == null || snapshot.columns() == null || !snapshot.hasRows()) {
            return List.of();
        }
        int at = -1;
        for (int i = 0; i < snapshot.columns().size(); i++) {
            String name = snapshot.columns().get(i);
            if (name != null && name.trim().equalsIgnoreCase(column.trim())) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (List<String> row : snapshot.rows()) {
            if (row != null && at < row.size() && row.get(at) != null && !row.get(at).isBlank()) {
                values.add(row.get(at).trim());
            }
        }
        return List.copyOf(values);
    }

    /**
     * 맥락에 실을 진행 상황 — 다음 한 걸음을 그대로 부를 수 있게 적어 준다. 진행 중인
     * 절차가 없으면 null(주입 안 함).
     */
    public String promptSection() {
        if (runs.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add(ChatPrompt.SECTION_PROGRESS);
        for (Run run : runs) {
            String head = "- " + run.skill() + label(run) + ": ";
            Step empty = run.emptyAt();
            if (empty != null) {
                lines.add(head + (empty.index() + 1) + "단계 조회 결과 0행 — 데이터가 없음이 확인됐다.");
                lines.add("  더 요청하지 말고 \"그 조건으로는 데이터가 없다\"고 답하라.");
                continue;
            }
            long done = run.steps().stream().filter(Step::arrived).count();
            int next = run.nextStep();
            if (next < 0) {
                lines.add(head + run.steps().size() + "단계 모두 도착 — 받은 데이터로 답하라.");
                continue;
            }
            lines.add(head + run.steps().size() + "단계 중 " + done + "단계 도착.");
            lines.add("  다음 = " + run.steps().get(next).title()
                    + " — " + DataRequestTool.NAME + "(queryId=\"" + run.skill() + "#" + next
                    + "\", args=" + argsJson(run.args()) + ")");
        }
        return String.join("\n", lines);
    }

    private static String label(Run run) {
        return run.argsPart().isEmpty() ? "" : " (" + run.argsPart().replace("&", ", ") + ")";
    }

    /** 그대로 베껴 쓸 수 있게 args 를 JSON 으로. 값은 이미 구분자가 접힌 상태다. */
    private static String argsJson(Map<String, String> args) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : args.entrySet()) {
            parts.add("\"" + escape(e.getKey()) + "\":\"" + escape(e.getValue()) + "\"");
        }
        return "{" + String.join(",", parts) + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

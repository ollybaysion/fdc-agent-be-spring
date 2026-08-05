package fdc.agent.chat;

import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.skills.NeedsResolver;
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
 * <p>진행을 어디에도 저장하지 않는 게 요점이다. {@link QueryKey} 가 한 절차의 모든
 * 조달에 같은 run 이름표를 달아 주므로, 이번 요청에 실려 온 스냅샷들의 키만 풀에
 * 비추면 그 절차가 무엇을 알아냈는지가 나온다. 계약에 {@code progress} 필드가 없어도
 * 되고, 서버가 세션을 들고 있지 않아도 된다 — <b>진행은 상태가 아니라 도착의 결과다.</b>
 *
 * <p>진행의 단위는 v3 에서 <b>단계에서 need 로</b> 바뀌었다. "3단계 중 1단계 도착"이
 * 아니라 "물리인지 가상인지는 알아냈고 VID 는 아직"이다 — 모델에게 남은 걸음을
 * 밀어 주는 문장이 조회의 이름이 아니라 <b>답에 모자란 것</b>을 말한다.
 *
 * <p>풀 형식이 아닌 키(옛 자유 저작 스냅샷)는 조용히 빠진다 — 적재도 억제도 그대로 받되
 * 진행으로는 읽지 않는다.
 */
public final class QueryProgress {

    /** 한 절차 = (스킬, run 이름표) 하나와 그 판정. */
    public record Run(
            String skill,
            String argsPart,
            Map<String, String> args,
            NeedsResolver.Resolution resolution) {
    }

    private final QueryPool pool;
    private final Map<String, ChatDataSnapshot> arrivedByKey;
    private final List<Run> runs;

    private QueryProgress(
            QueryPool pool, Map<String, ChatDataSnapshot> arrivedByKey, List<Run> runs) {
        this.pool = pool;
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
            QueryPool.Query query = parsed == null ? null : pool.byId(parsed.queryId());
            if (query == null) {
                continue; // 풀 밖 키 — 억제엔 쓰이되 진행으로는 읽지 않는다.
            }
            // 스킬 이름은 풀의 것으로 쓴다 — 키의 표기가 흔들려도 needs 를 못 찾는 일이
            // 없게(byId 는 표기 차이를 흡수하지만 needsOf 는 정식 이름만 안다).
            seen.add(new RunId(query.skill(), parsed.argsPart()));
        }

        List<Run> runs = new ArrayList<>();
        for (RunId id : seen) {
            NeedsResolver.Rows rows = rowsOf(arrived, id.skill(), id.argsPart());
            NeedsResolver.Resolution resolution = NeedsResolver.resolve(pool.needsOf(id.skill()),
                    rows, NeedsResolver.reachOf(pool.wiringOf(id.skill()), rows));
            runs.add(new Run(id.skill(), id.argsPart(),
                    QueryKey.parseArgs(id.argsPart()), resolution));
        }
        return new QueryProgress(pool, Map.copyOf(arrived), List.copyOf(runs));
    }

    /**
     * 판정기가 도착 데이터를 읽는 창구 — 이 run 의 이름표를 붙여 키를 만들어 본다.
     * 미도착이면 {@code null}, 도착했으면 그 컬럼의 값들(0행이면 빈 목록).
     */
    static NeedsResolver.Rows rowsOf(
            Map<String, ChatDataSnapshot> arrived, String skill, String argsPart) {
        return (queryId, column) -> {
            String key = argsPart == null || argsPart.isEmpty()
                    ? skill + "#" + queryId
                    : skill + "#" + queryId + "__" + argsPart;
            ChatDataSnapshot hit = arrived.get(key);
            return hit == null ? null : NeedsResolver.Cell.of(valuesOf(hit, column));
        };
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
     * 맥락에 실을 진행 상황 — 알아낸 것과 모자란 것, 그리고 다음 한 걸음을 그대로
     * 부를 수 있게 적어 준다. 진행 중인 절차가 없으면 null(주입 안 함).
     */
    public String promptSection() {
        if (runs.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add(ChatPrompt.SECTION_PROGRESS);
        for (Run run : runs) {
            lines.add("- " + run.skill() + label(run) + ":");
            List<NeedsResolver.NeedStatus> filled =
                    run.resolution().in(NeedsResolver.State.FILLED);
            if (!filled.isEmpty()) {
                lines.add("  알아낸 것: " + String.join(", ", whats(filled)));
            }
            List<NeedsResolver.NeedStatus> unmet = run.resolution().unmet();
            if (!unmet.isEmpty()) {
                lines.add("  아직 모르는 것: " + String.join(", ", whats(unmet)));
            }
            lines.addAll(verdictLines(run));
        }
        return String.join("\n", lines);
    }

    /** 판정에 따른 지시 — 조달할 것이 있으면 그 한 줄, 없으면 답하라는 지시다. */
    private List<String> verdictLines(Run run) {
        return switch (run.resolution().outcome()) {
            case SUFFICIENT -> List.of("  알아야 할 것을 모두 확인했다 — 받은 데이터로 답하라.");
            case UNANSWERABLE -> List.of(
                    "  더 조달할 수단이 없다 — 모르는 것은 확인되지 않았다는 사실로 답하고,"
                            + " 더 요청하지 마라.");
            case PROCURABLE -> {
                String next = run.resolution().wanted().stream().findFirst().orElse(null);
                QueryPool.Query query = next == null ? null : pool.byId(run.skill() + "#" + next);
                if (query == null) {
                    yield List.of();
                }
                yield List.of("  다음 = " + query.label() + " — " + DataRequestTool.NAME
                        + "(queryId=\"" + query.queryId() + "\", args=" + argsJson(run.args()) + ")");
            }
        };
    }

    private static List<String> whats(List<NeedsResolver.NeedStatus> needs) {
        return needs.stream().map(NeedsResolver.NeedStatus::what).toList();
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

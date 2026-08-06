package fdc.agent.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.DataRequest;
import fdc.agent.contract.RunDecl;
import fdc.agent.skills.NeedsResolver;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillSpec;
import fdc.agent.skills.SqlRender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code retrieve_data} — <b>need 단위</b> 조달 왕복. 모델은 "무엇을 알아야 하는가"를
 * 말하고, 그것을 어떤 조회로 얻을지는 BE 가 결정론으로 정한다.
 *
 * <p>단위가 need 인 것이 v2 의 {@code request_data}(쿼리 단위)와 갈리는 지점이다.
 * 요청한 조달과 <b>다른 경로</b>로 도착해도 그 사실이 채워졌으면 채워진 것인데,
 * 요청의 단위가 쿼리이면 그 도착을 표현할 자리가 없어 이미 아는 것을 다시
 * 조회시키게 된다. 반환의 {@code arrived[]} 가 항목마다 {@code source} 를 다는
 * 이유도 같다 — 시킨 조회로 온 것인지 다른 경로로 온 것인지를 답이 인용할 수 있어야 한다.
 *
 * <p><b>모델은 SQL 을 짓지 않는다.</b> 조달 수단은 {@link QueryPool} 에 등재된 것뿐이고
 * 문장·조회 키·기대 컬럼은 서버가 만든다 — 사용자가 사내에서 자기 권한으로 실행할
 * 문장을 모델 저작에 맡기지 않는다.
 *
 * <p><b>실행하는 것은 사람이다.</b> 이 툴은 조회를 돌리지 않고 요청 카드를 모은다.
 * 그래서 한 번의 호출에 도착·요청·잠김이 함께 돌아온다: 이미 온 것은 그 자리에서
 * 값으로, 지금 열 수 있는 것은 카드로, 아직 못 여는 것은 사유로.
 *
 * <p>인스턴스는 요청 단위다 — 수집 상태(이미 요청한 키, 모은 요청)를 자기가 들고 있다.
 */
public final class RetrieveDataTool implements AgentTool {

    public static final String NAME = NarrationPrompt.TOOL_NAME;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 한 번의 반환에 실을 행 수 상한 — 표 전량은 화면에 있고, 여기 실리는 것은 근거다. */
    private static final int MAX_ROWS = 50;

    private final QueryPool pool;
    private final QueryProgress progress;

    /** 이번 응답에서 이미 요청한 키 — 한 응답 안의 중복도 막는다. */
    private final Set<String> requested = new LinkedHashSet<>();

    private final List<DataRequest> collected = new ArrayList<>();

    public RetrieveDataTool(QueryPool pool, QueryProgress progress) {
        this.pool = pool;
        this.progress = progress;
    }

    /** 이번 응답에서 모인 조달 요청(억제된 것은 빠져 있다). */
    public List<DataRequest> collected() {
        return List.copyOf(collected);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "질문에 답하려면 알아야 할 것(need)을 대면 그것을 채울 조회를 서버가 골라 준다. "
                + "이미 도착한 것은 값으로 그 자리에서 돌아오고, 아직 없는 것은 사용자에게 요청 카드로 "
                + "나간다 — 사용자가 SQL 을 실행해 결과를 붙여넣으면 다음 질문에 실려 온다. "
                + "SQL 과 조회 키는 서버가 만든다.";
    }

    @Override
    public String guidance() {
        return "데이터가 필요하면 값을 지어내지 말고 " + NAME + " 툴로 요청하라 — 스킬(skill)과 "
                + "그 인자(args)만 주면 되고, 무엇을 조회할지는 서버가 정한다. "
                + "알아야 할 것을 특정하고 싶으면 needs 에 그 id 를 적되, 비워 두면 서버가 전량을 본다. "
                + "반환의 arrived 는 이미 확인된 사실이니 다시 요청하지 말고 그대로 쓰고, "
                + "blocked 는 앞 조달이 와야 열리는 것이라 그 조달이 도착한 뒤에 다시 부르면 된다. "
                + "outcome 이 unanswerable 이면 더 요청하지 말고 확인되지 않았다는 사실로 답하라.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("skill", ToolArgs.stringEnum(
                "어느 절차인가. 등재된 스킬:\n" + skillCatalog(), skills()));
        props.put("args", ToolArgs.stringMap(
                "스킬 인자 값 — 목록의 (인자: …) 이름 그대로. 예: {\"snsr_id\": \"S-0004\"}"));
        props.put("needs", ToolArgs.stringArray(
                "알아야 할 것의 id — 목록의 needs 에 적힌 그대로. 비우면 그 스킬이 알아야 할 것 전량."));
        return ToolArgs.schema(props, List.of("skill", "args"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String skill = skillOf(ToolArgs.text(args, "skill"));
        if (skill == null) {
            return ToolResult.of(error("등재된 스킬이 아닙니다. 아래에서 고르세요.\n" + skillCatalog()));
        }

        Map<String, String> runArgs = resolveArgs(skill, ToolArgs.map(args, "args"));
        if (runArgs == null) {
            return ToolResult.of(error("어느 절차인지 인자로 지정하세요 — " + skill
                    + " 로 진행 중인 절차가 여럿입니다: " + runLabels(skill)));
        }
        List<String> missing = missingArgs(skill, runArgs);
        if (!missing.isEmpty()) {
            return ToolResult.of(error("인자가 모자라 요청하지 못했습니다: " + String.join(", ", missing)
                    + ". 값을 모르면 " + InputRequestTool.NAME + " 으로 사용자에게 물으세요."));
        }

        String argsPart = QueryKey.argsPart(runArgs, pool.requiredArgsOf(skill));
        NeedsResolver.Rows rows = QueryProgress.rowsOf(progress.arrivedByKey(), skill, argsPart);
        NeedsResolver.Resolution resolution = NeedsResolver.resolve(
                pool.needsOf(skill), rows, NeedsResolver.reachOf(pool.wiringOf(skill), rows));

        return ToolResult.of(report(skill, runArgs, argsPart, resolution,
                targets(skill, resolution, ToolArgs.strings(args, "needs"))));
    }

    // ── 무엇을 열 것인가 ─────────────────────────────────────────────────────

    /**
     * 이번 호출에서 열 조달들. 모델이 need 를 특정했으면 그 need 가 지목한 것 중
     * 아직 안 온 것만, 아니면 판정이 이미 계산해 둔 {@code wanted} 전량이다.
     *
     * <p>어느 쪽이든 <b>판정이 최종 권한</b>이다: 모델이 지목한 need 가 이미 찼거나
     * 갈래 밖이면 그 조달은 열지 않는다. 모델의 재량은 "무엇이 궁금한가"까지고
     * "무엇을 돌릴 수 있는가"는 결정론이다.
     */
    private List<String> targets(
            String skill, NeedsResolver.Resolution resolution, List<String> wantedNeeds) {
        if (wantedNeeds == null || wantedNeeds.isEmpty()) {
            return resolution.wanted();
        }
        Set<String> asked = new LinkedHashSet<>(wantedNeeds);
        Set<String> unfilled = new LinkedHashSet<>();
        for (NeedsResolver.NeedStatus n : resolution.in(NeedsResolver.State.UNFILLED)) {
            unfilled.add(n.id());
        }
        Set<String> out = new LinkedHashSet<>();
        for (SkillSpec.SkillNeed need : pool.needsOf(skill)) {
            if (need == null || !asked.contains(need.id()) || !unfilled.contains(need.id())) {
                continue;
            }
            for (SkillSpec.Fill fill : need.fills()) {
                if (fill != null && resolution.wanted().contains(fill.query())) {
                    out.add(fill.query());
                }
            }
        }
        return List.copyOf(out);
    }

    // ── 반환 ────────────────────────────────────────────────────────────────

    /**
     * 한 번의 왕복이 돌려주는 것 전부 — 도착·요청·잠김·판정. 재생 프롬프트
     * ({@link NarrationPrompt})의 tool 메시지와 {@code arrived[]} 모양이 같다:
     * 스킬로 굴리든 추론으로 굴리든 같은 대화가 남아야 한다.
     */
    private String report(
            String skill, Map<String, String> runArgs, String argsPart,
            NeedsResolver.Resolution resolution, List<String> targets) {
        List<Map<String, Object>> arrived = new ArrayList<>();
        List<Map<String, Object>> requests = new ArrayList<>();
        List<Map<String, Object>> blocked = new ArrayList<>();

        for (String queryId : targets) {
            QueryPool.Query query = pool.byId(skill + "#" + queryId);
            if (query == null) {
                continue;
            }
            String queryKey = QueryKey.of(skill, query.id(), runArgs, query.requiredArgs());
            ChatDataSnapshot already = progress.arrived(queryKey);
            if (already != null) {
                arrived.add(arrivedItem(query, already));
                continue;
            }
            open(query, runArgs, queryKey, requests, blocked);
        }

        // 이미 알고 있는 것도 함께 돌려준다 — 모델이 그것을 다시 요청하지 않게.
        for (NeedsResolver.NeedStatus n : resolution.in(NeedsResolver.State.FILLED)) {
            arrived.add(filledItem(skill, n, argsPart));
        }
        for (NeedsResolver.NeedStatus n : resolution.unmet()) {
            if (!isTargeted(skill, n.id(), targets)) {
                blocked.add(blockedNeed(n));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arrived", arrived);
        out.put("requested", requests);
        out.put("blocked", blocked);
        out.put("outcome", resolution.outcome().name().toLowerCase());
        return stringify(out);
    }

    /** 이 조달을 지금 열 수 있나 — 열리면 카드로, 아니면 사유로. */
    private void open(
            QueryPool.Query query, Map<String, String> runArgs, String queryKey,
            List<Map<String, Object>> requests, List<Map<String, Object>> blocked) {
        if (!requested.add(queryKey)) {
            return; // 이번 응답에서 이미 나갔다.
        }
        BindOutcome outcome = BindResolver.resolve(query, runArgs, null, progress::arrived);
        if (!(outcome instanceof BindOutcome.Ready ready)) {
            requested.remove(queryKey); // 나가지 못한 요청은 재시도를 막지 않는다.
            blocked.add(blockedQuery(query, prose(outcome)));
            return;
        }
        String sql;
        try {
            sql = SqlRender.render(query.sql(), ready.binds());
        } catch (IllegalArgumentException bad) {
            requested.remove(queryKey);
            blocked.add(blockedQuery(query, "조회 문장을 완성하지 못했습니다: " + bad.getMessage()));
            return;
        }
        List<String> columns = SqlRender.columnsOf(query.sql());
        collected.add(new DataRequest(queryKey, query.label(), sql, columns,
                new RunDecl(query.skill(), runArgs)));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("query", query.queryId());
        item.put("what", query.label());
        item.put("sql", sql);
        if (columns != null && !columns.isEmpty()) {
            item.put("columns", columns);
        }
        requests.add(item);
    }

    private static Map<String, Object> arrivedItem(QueryPool.Query query, ChatDataSnapshot full) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", query.queryId());
        if (query.table() != null) {
            item.put("table", query.table());
        }
        item.put("columns", full.columns() != null ? full.columns() : List.of());
        item.put("rows", full.hasRows() ? capped(full.rows()) : List.of());
        return item;
    }

    /**
     * 이미 채워진 need 한 줄. 값 실물은 {@code arrived} 의 표에 있으므로 여기서는
     * <b>어디서 왔는지</b>만 적는다 — 같은 사실을 두 번 실으면 표가 길어질수록 답이
     * 흐려진다.
     */
    private static Map<String, Object> filledItem(
            String skill, NeedsResolver.NeedStatus need, String argsPart) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", skill + (argsPart.isEmpty() ? "" : " (" + argsPart + ")"));
        item.put("need", need.id());
        item.put("what", need.what());
        if (need.value() != null) {
            item.put("value", need.value());
        }
        return item;
    }

    private static Map<String, Object> blockedQuery(QueryPool.Query query, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("query", query.queryId());
        item.put("what", query.label());
        item.put("reason", reason);
        return item;
    }

    private static Map<String, Object> blockedNeed(NeedsResolver.NeedStatus need) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("need", need.id());
        item.put("what", need.what());
        item.put("reason", switch (need.state()) {
            case UNPROCURABLE -> "조달 수단이 없거나 빈손으로 확인됐다 — 다른 방식으로 받을 수 있으면 받는다.";
            case PENDING_GATE -> "이것이 필요한지는 앞 조달 결과가 정한다.";
            default -> "앞 조달이 도착해야 열린다.";
        });
        return item;
    }

    private boolean isTargeted(String skill, String needId, List<String> targets) {
        for (SkillSpec.SkillNeed need : pool.needsOf(skill)) {
            if (need == null || !needId.equals(need.id())) {
                continue;
            }
            for (SkillSpec.Fill fill : need.fills()) {
                if (fill != null && targets.contains(fill.query())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── 인자·목록 ───────────────────────────────────────────────────────────

    /** 표기 흔들림 흡수 — 툴 이름은 {@code fdc_explain_sensor}, spec 이름은 하이픈이다. */
    private String skillOf(String given) {
        if (given == null) {
            return null;
        }
        String raw = given.trim();
        for (String skill : skills()) {
            if (normalize(skill).equals(normalize(raw))) {
                return skill;
            }
        }
        QueryPool.Query byQuery = pool.byId(raw);
        return byQuery != null ? byQuery.skill() : null;
    }

    private List<String> skills() {
        List<String> out = new ArrayList<>();
        for (QueryPool.Query q : pool.all()) {
            if (!out.contains(q.skill())) {
                out.add(q.skill());
            }
        }
        return out;
    }

    /** 스킬 한 줄 = 인자와 알아야 할 것들. 모델이 needs 를 적으려면 id 가 보여야 한다. */
    private String skillCatalog() {
        List<String> lines = new ArrayList<>();
        for (String skill : skills()) {
            StringBuilder line = new StringBuilder("- ").append(skill);
            List<String> required = pool.requiredArgsOf(skill);
            if (!required.isEmpty()) {
                line.append(" (인자: ").append(String.join(", ", required)).append(")");
            }
            List<String> needs = new ArrayList<>();
            for (SkillSpec.SkillNeed need : pool.needsOf(skill)) {
                if (need != null && need.id() != null) {
                    needs.add(need.id() + "=" + need.what());
                }
            }
            if (!needs.isEmpty()) {
                line.append("\n  needs: ").append(String.join(" / ", needs));
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    /**
     * 모델이 준 인자에 진행 중인 run 의 인자를 덧댄다 — 이어가기에서 인자를 빼먹는 일이
     * 잦은데, 그 스킬로 진행 중인 절차가 하나뿐이면 어느 run 인지는 모호하지 않다.
     * 둘 이상이면 채우지 않고 {@code null}(어느 절차인지 물어야 한다).
     */
    private Map<String, String> resolveArgs(String skill, Map<String, String> given) {
        Map<String, String> out = new LinkedHashMap<>(given);
        if (missingArgs(skill, out).isEmpty()) {
            return out;
        }
        List<QueryProgress.Run> runs = progress.runsOf(skill);
        if (runs.size() > 1) {
            return null;
        }
        if (runs.size() == 1) {
            runs.get(0).args().forEach(out::putIfAbsent);
        }
        return out;
    }

    private List<String> missingArgs(String skill, Map<String, String> args) {
        return pool.requiredArgsOf(skill).stream()
                .filter(name -> !args.containsKey(name) || args.get(name).isBlank())
                .toList();
    }

    private String runLabels(String skill) {
        return String.join(" / ", progress.runsOf(skill).stream()
                .map(r -> r.argsPart().replace("&", ", "))
                .toList());
    }

    private static List<List<String>> capped(List<List<String>> rows) {
        return rows.size() <= MAX_ROWS ? rows : rows.subList(0, MAX_ROWS);
    }

    /**
     * 타입 결과({@link BindResolver}) → 모델에 되먹일 사유. 문구는 쿼리 단위 툴에서
     * 쓰던 것과 같다 — 모델이 붙는 표면을 단위 변경이 흔들지 않는다.
     */
    private static String prose(BindOutcome outcome) {
        return switch (outcome) {
            case BindOutcome.MissingUpstream m -> m.query() + " 결과가 먼저 필요합니다.";
            case BindOutcome.EmptyUpstream e -> e.query() + " 조회 결과가 0행이라 이어갈 수 없습니다"
                    + " — 데이터가 없다는 사실로 답하세요.";
            case BindOutcome.NeedPick p -> p.query() + " 결과의 " + p.column()
                    + " 이 여러 값입니다. " + BindResolver.candidates(p.candidates());
            case BindOutcome.Blocked b -> b.reason();
            case BindOutcome.Ready r -> throw new IllegalStateException("Ready 는 되먹일 사유가 없다");
        };
    }

    private static String error(String message) {
        return stringify(Map.of("error", message));
    }

    private static String stringify(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"응답을 만들지 못했습니다.\"}";
        }
    }

    private static String normalize(String name) {
        return name.replace('-', '_').toLowerCase();
    }
}

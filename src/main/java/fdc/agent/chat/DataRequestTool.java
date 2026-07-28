package fdc.agent.chat;

import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.DataRequest;
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
 * {@code request_data} — 조회 툴로 닿지 않는 데이터를 <b>사용자에게 조달 요청</b>하는
 * 수집 툴. 실행하지 않고 요청을 모아 두었다가, 루프가 끝나면 done 페이로드의
 * {@code dataRequests} → FE 요청 카드로 나간다.
 *
 * <p><b>모델은 고르기만 한다.</b> 요청 가능한 조회는 {@link QueryPool}(스킬 spec 의
 * {@code steps[]})에 등재된 것뿐이고, 모델이 주는 것은 {@code queryId} 와 스킬 인자
 * 값뿐이다. SQL 도 {@code queryKey} 도 기대 컬럼도 BE 가 만든다 — 사용자가 사내에서
 * 자기 권한으로 실행할 문장을 모델이 저작하게 두지 않는다.
 *
 * <p><b>이어가기는 도착에서 나온다.</b> 앞 단계 결과를 바인드로 쓰는 조회는 그 단계의
 * 스냅샷이 도착해 있어야 나갈 수 있고, 값은 도착한 표에서 읽는다. 여러 값이면 모델이
 * {@code pick} 으로 고르되 <b>실제로 있는 값 중에서만</b> 고른다 — 갈림길은 맡기고
 * 창작은 막는다. 0행으로 확인된 단계에 매달린 조회는 이어가지 않는다: 없다는 것도
 * 사실이고, 그 사실로 답하는 게 맞다.
 *
 * <p><b>억제가 왕복을 끝낸다</b>: 이미 도착한 키이거나 이번 응답에서 이미 요청한 키면
 * 카드로 내보내지 않는다. 여기서 결정론적으로 확정하므로 모델이 헷갈려도 같은 데이터를
 * 무한히 다시 요청하지 못한다. 반대로 <b>키만 있고 내용이 안 온 항목은 억제하지
 * 않는다</b> — 그건 아직 안 받은 것이라 다시 요청할 수 있어야 한다.
 *
 * <p>인스턴스는 요청 단위다 — 수집 상태(이미 요청한 키, 모은 요청)를 자기가 들고 있다.
 */
public final class DataRequestTool implements AgentTool {

    public static final String NAME = "request_data";

    /** 여러 값 중 고르라고 되먹일 때 보여 줄 후보 수 상한. */
    private static final int MAX_CANDIDATES = 10;

    private final QueryPool pool;
    private final QueryProgress progress;

    /** 이번 응답에서 이미 요청한 키 — 한 응답 안의 중복도 막는다. */
    private final Set<String> requested = new LinkedHashSet<>();

    private final List<DataRequest> collected = new ArrayList<>();

    public DataRequestTool(QueryPool pool, QueryProgress progress) {
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
        return "등재된 조회 목록에서 하나를 골라 사용자에게 조달을 요청한다 — 여기서 실행하지는 않는다. "
                + "요청은 화면에 카드로 뜨고, 사용자가 실행 가능한 SQL 을 복사해 돌린 뒤 결과를 붙여넣으면 "
                + "다음 질문에 실려 온다. SQL 과 조회 키는 서버가 만든다.";
    }

    @Override
    public String guidance() {
        return "조회할 수 없는 데이터가 필요하면 값을 지어내지 말고 " + NAME
                + " 툴로 등재된 조회 목록에서 골라 요청하라 — queryId 와 스킬 인자(args)만 주면 된다"
                + "(SQL·조회 키는 서버가 만든다). 목록에 없는 조회는 요청할 수 없다. "
                + ChatPrompt.SECTION_PROGRESS + " 에 \"다음 =\" 이 적혀 있으면 그 줄대로 이어서 요청하고, "
                + "바인드가 준비된 단계가 여럿이면 한 응답에서 여러 번 불러도 된다. "
                + "0행으로 끝난 절차는 더 요청하지 말고 데이터가 없다는 사실로 답하라.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("queryId", ToolArgs.stringEnum(
                "요청할 조회. 등재된 목록:\n" + pool.catalogText(), pool.ids()));
        props.put("args", ToolArgs.stringMap(
                "스킬 인자 값 — 목록의 (인자: …) 이름 그대로. 예: {\"snsr_id\": \"S-0004\"}"));
        props.put("pick", ToolArgs.stringMap(
                "앞 단계 결과가 여러 행일 때만 — 어느 값으로 이어갈지. 컬럼명: 값. "
                        + "예: {\"EQP_ID\": \"CVD-01\"}. 그 컬럼에 실제로 있는 값만 쓸 수 있다."));
        return ToolArgs.schema(props, List.of("queryId", "args"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String queryId = ToolArgs.text(args, "queryId");
        if (queryId == null) {
            return ToolResult.of("데이터 요청이 형식에 맞지 않아 등록하지 못했습니다(queryId 필요).");
        }
        QueryPool.Query query = pool.byId(queryId);
        if (query == null) {
            return ToolResult.of("등재된 조회가 아닙니다: " + queryId
                    + ". 임의 SQL 은 요청할 수 없습니다 — 아래 목록에서 고르세요.\n" + pool.catalogText()
                    + "\n필요한 조회가 목록에 없으면, 지금 가진 것으로 답하고 그 조회는 스킬로 등재해야 한다고"
                    + " 사용자에게 알리세요.");
        }

        Map<String, String> runArgs = resolveArgs(query, ToolArgs.map(args, "args"));
        if (runArgs == null) {
            return ToolResult.of("어느 절차의 " + (query.step() + 1) + "단계인지 인자로 지정하세요 — "
                    + query.skill() + " 로 진행 중인 절차가 여럿입니다: " + runLabels(query.skill()));
        }
        List<String> missing = missingArgs(query, runArgs);
        if (!missing.isEmpty()) {
            return ToolResult.of("인자가 모자라 요청하지 못했습니다: " + String.join(", ", missing)
                    + ". 값을 모르면 " + InputRequestTool.NAME + " 으로 사용자에게 물으세요.");
        }

        String queryKey = QueryKey.of(query.skill(), query.step(), runArgs, query.requiredArgs());
        ChatDataSnapshot already = progress.arrived(queryKey);
        if (already != null) {
            return ToolResult.of(already.isEmptyResult()
                    ? "이 조회는 이미 0행으로 확인됐습니다: " + query.title()
                            + " — 데이터가 없다는 사실로 답하라."
                    : "이미 도착한 데이터입니다: " + query.title() + " — 그 데이터로 분석을 이어가라.");
        }
        if (!requested.add(queryKey)) {
            return ToolResult.of("이번 응답에서 이미 요청한 데이터입니다: " + query.title());
        }

        Map<String, String> binds;
        try {
            binds = resolveBinds(query, runArgs, ToolArgs.map(args, "pick"));
        } catch (IllegalArgumentException blocked) {
            requested.remove(queryKey); // 나가지 못한 요청은 재시도를 막지 않는다.
            return ToolResult.of(blocked.getMessage());
        }

        String sql;
        try {
            sql = SqlRender.render(query.sql(), binds);
        } catch (IllegalArgumentException bad) {
            requested.remove(queryKey);
            return ToolResult.of("조회 문장을 완성하지 못했습니다: " + bad.getMessage());
        }

        String label = query.title() + argsSuffix(runArgs, query.requiredArgs());
        collected.add(new DataRequest(queryKey, label, sql, SqlRender.columnsOf(query.sql())));
        return ToolResult.of("데이터 요청을 등록했습니다: " + label
                + ". 데이터 패널 카드의 SQL 을 실행해 결과를 붙여넣어 등록해 주세요"
                + " — 조회 결과가 없으면 \"결과 없음\"으로 등록하시면 그것도 사실로 받습니다."
                + " 등록 후 채팅에 알려주시면 이어서 분석합니다. 없는 값은 지어내지 않습니다.");
    }

    /**
     * 모델이 준 인자에 진행 중인 run 의 인자를 덧댄다 — 이어가기에서 인자를 빼먹는 일이
     * 잦은데, 그 스킬로 진행 중인 절차가 하나뿐이면 어느 run 인지는 모호하지 않다.
     * 둘 이상이면 채우지 않고 {@code null}(어느 절차인지 물어야 한다).
     */
    private Map<String, String> resolveArgs(QueryPool.Query query, Map<String, String> given) {
        Map<String, String> out = new LinkedHashMap<>(given);
        if (missingArgs(query, out).isEmpty()) {
            return out;
        }
        List<QueryProgress.Run> runs = progress.runsOf(query.skill());
        if (runs.size() > 1) {
            return null;
        }
        if (runs.size() == 1) {
            runs.get(0).args().forEach(out::putIfAbsent);
        }
        return out;
    }

    private static List<String> missingArgs(QueryPool.Query query, Map<String, String> args) {
        return query.requiredArgs().stream()
                .filter(name -> !args.containsKey(name) || args.get(name).isBlank())
                .toList();
    }

    private String runLabels(String skill) {
        return String.join(" / ", progress.runsOf(skill).stream()
                .map(r -> r.argsPart().replace("&", ", "))
                .toList());
    }

    /**
     * SQL 에 박을 값들. {@code from:"arg"} 는 인자에서, {@code from:"step"} 은 <b>도착한
     * 앞 단계 스냅샷에서</b> 읽는다.
     *
     * @throws IllegalArgumentException 이어갈 수 없는 사유(그대로 모델에 되먹인다)
     */
    private Map<String, String> resolveBinds(
            QueryPool.Query query, Map<String, String> runArgs, Map<String, String> pick) {
        Map<String, String> binds = new LinkedHashMap<>();
        for (Map.Entry<String, SkillSpec.BindSource> e : query.binds().entrySet()) {
            SkillSpec.BindSource src = e.getValue();
            if ("arg".equals(src.from())) {
                String value = runArgs.get(src.arg());
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("인자 " + src.arg() + " 값이 필요합니다.");
                }
                binds.put(e.getKey(), value);
                continue;
            }
            binds.put(e.getKey(), fromEarlierStep(query, runArgs, pick, src));
        }
        return binds;
    }

    /** 앞 단계 스냅샷에서 한 값을 고른다 — 1행이면 그대로, 여러 행이면 pick, 없으면 중단. */
    private String fromEarlierStep(
            QueryPool.Query query, Map<String, String> runArgs, Map<String, String> pick,
            SkillSpec.BindSource src) {
        if (src.step() == null) {
            // 로드 시 검증(SkillLoader.validateBinds)이 걸렀어야 할 spec — 조용히 이상한
            // 문장을 만드느니 여기서 멈춘다.
            throw new IllegalArgumentException("이 조회의 배선이 온전하지 않아 요청할 수 없습니다.");
        }
        int step = src.step();
        String sourceKey = QueryKey.of(query.skill(), step, runArgs, query.requiredArgs());
        ChatDataSnapshot source = progress.arrived(sourceKey);
        if (source == null) {
            throw new IllegalArgumentException((step + 1) + "단계 결과가 먼저 필요합니다 — queryId=\""
                    + query.skill() + "#" + step + "\" 을 먼저 요청하세요.");
        }
        if (source.isEmptyResult()) {
            throw new IllegalArgumentException((step + 1) + "단계 조회 결과가 0행이라 이어갈 수 없습니다"
                    + " — 데이터가 없다는 사실로 답하세요.");
        }

        List<String> values = QueryProgress.valuesOf(source, src.column());
        if (values.isEmpty()) {
            throw new IllegalArgumentException((step + 1) + "단계 결과에서 " + src.column()
                    + " 컬럼 값을 찾지 못했습니다 — 붙여넣은 표에 그 컬럼이 있는지 확인해 주세요.");
        }

        String picked = pick.get(src.column());
        if (picked == null) {
            for (Map.Entry<String, String> p : pick.entrySet()) {
                if (p.getKey().equalsIgnoreCase(src.column())) {
                    picked = p.getValue();
                    break;
                }
            }
        }
        if (picked != null) {
            for (String v : values) {
                if (v.equalsIgnoreCase(picked)) {
                    return v; // 표에 있는 값만 통과 — 고르되 지어내지는 못한다.
                }
            }
            throw new IllegalArgumentException("pick 한 " + src.column() + "=" + picked
                    + " 은 " + (step + 1) + "단계 결과에 없는 값입니다. " + candidates(values));
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException((step + 1) + "단계 결과의 " + src.column()
                    + " 이 여러 값입니다 — pick 으로 하나를 고르세요. " + candidates(values));
        }
        return values.get(0);
    }

    private static String candidates(List<String> values) {
        List<String> shown = values.size() > MAX_CANDIDATES ? values.subList(0, MAX_CANDIDATES) : values;
        return "가능한 값: " + String.join(", ", shown)
                + (values.size() > shown.size() ? " 외 " + (values.size() - shown.size()) + "개" : "");
    }

    /** 카드 라벨 꼬리 — 같은 조회가 대상만 다를 때 사람이 구분할 수 있게. */
    private static String argsSuffix(Map<String, String> args, List<String> names) {
        List<String> parts = new ArrayList<>();
        for (String name : names) {
            String value = args.get(name);
            if (value != null && !value.isBlank()) {
                parts.add(name + "=" + value);
            }
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }
}

package fdc.agent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.chat.PanelJudge.Narration;
import fdc.agent.chat.PanelJudge.QueryArrival;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.QueryScope;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.schema.SchemaDoc;
import fdc.agent.schema.SchemaSource;
import fdc.agent.skills.NeedsResolver;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillSpec;
import fdc.agent.skills.SqlRender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 종결 서술 호출의 프롬프트 합성 — <b>절차가 실제로 추론으로 굴러갔다면 남았을
 * 메시지 배열을 결정론으로 재생한다</b>.
 *
 * <p>합격 기준이 이것 하나다: <b>스킬로 실행하든 LLM 이 실제로 추론해서 실행하든
 * 같은 모양의 대화가 남아야 한다.</b> 스킬은 네 칸(질문 → rephrasing → needs →
 * 조달)의 추론을 미리 채워 둔 정답지이지 다른 종류의 실행이 아니므로, 그 결과물이
 * 추론 경로에는 없는 문서 양식(맥락 섹션·데이터 절)으로 도착하면 안 된다. 그래서
 * 이 클래스는 markdown 문서를 만들지 않고 <b>대화를 만든다</b>:
 *
 * <pre>
 *   system     정체성
 *   user       (이력) 사용자가 실제로 친 말
 *   assistant  계획      = rephrasing + needs 전량(어디서 채우나·조건까지)
 *   assistant  조달 계획 = 무엇을 어떤 순서로 얻을 것인가
 *   assistant  act       = retrieve_data 호출 (라운드마다)
 *   tool       도착      = 그 라운드에 온 데이터
 *   assistant  가교      = 다음 라운드 바인드 값이 어디서 나왔는지 (라운드 사이)
 *   assistant  판정      = 무엇이 찼고 무엇이 어떻게 비었나 (결정론)
 *   system     서술 규칙
 *   user       지시
 * </pre>
 *
 * <p><b>계획과 판정은 단계가 다르다.</b> 계획은 저작 시점에 정해진 정적 목록이고
 * (그래서 판정 전 <b>전량</b>이 실린다), 판정은 데이터가 도착한 뒤에 생기는 관찰이다.
 * 예전 구현은 둘을 곱해서 「반드시 포함 = 채워진 need」한 줄로 눌러 놓았고, 그래서
 * "원래 무엇을 알아보려 했는가"가 프롬프트에서 사라져 있었다.
 *
 * <p><b>조달 계획과 act 도 다르다.</b> 이 제품에서 조달을 실행하는 것은 모델이
 * 아니라 사람이다(카드의 SQL 을 복붙한다). 그래서 "계획에는 있는데 끝내 호출되지
 * 않은 조달"이 존재하고, 그것이 판정의 「해당 없음」과 짝을 이룬다 — 계획을 act 에
 * 녹여 버리면 안 한 조회와 해서 0행인 조회가 구분되지 않는다.
 */
public final class NarrationPrompt {
    private NarrationPrompt() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 조달 툴 이름 — 인자는 {@code needs[]}(무엇을 알아야 하나) + {@code queries[]}
     * (어떻게 얻나)이고 반환은 {@code arrived[]}. <b>need 단위</b>인 것이 핵심이다:
     * 요청한 조달과 다른 경로로 도착해도 그 사실이 채워졌으면 채워진 것이므로,
     * 요청의 단위가 쿼리이면 그 도착을 표현할 자리가 없다.
     */
    public static final String TOOL_NAME = "retrieve_data";

    /**
     * 종결 서술 지시 — 마지막 user 메시지의 전문. 목({@code MockLlm})과 테스트는
     * 문장이 아니라 이 상수에 붙는다: 문구를 고쳐도 상수를 참조하는 쪽은 안 깨진다.
     */
    public static final String NARRATE_INSTRUCTION = "지금까지 확인된 것으로 사용자의 질문에 답하라.";

    /**
     * 정체성 — 툴 규율이 없다(재생이 끝난 시점이라 더 부를 툴이 없다). 서식 문장은
     * {@link ChatPrompt} 의 IDENTITY 와 같은 이유다: FE 가 Markdown(GFM)으로 렌더한다.
     */
    static final String IDENTITY = String.join(" ",
            "당신은 반도체 설비 이상탐지(FDC) 분석 어시스턴트다.",
            "제공되지 않은 값은 지어내지 않는다.",
            "답변은 한국어로 간결하게 작성하고, 서식은 Markdown(GFM)으로 한다 —",
            "강조는 **굵게**, 구조가 필요하면 목록(-)·인용(>)을 쓰되 원시 HTML 은 쓰지 않는다.",
            "제공된 데이터 표는 화면에도 표시되니 본문에 같은 표를 다시 그리지 말고 해석·요약에 집중한다.");

    /** 서술 규칙의 첫 줄 — 하지 말 것이 없어도 이건 나간다. */
    static final String NO_INVENTION = "확인되지 않은 것은 지어내지 말고 확인되지 않았다고 적는다.";

    /**
     * 종결 서술 한 요청의 메시지 전부.
     *
     * @param spec 서술 대상 run 의 스킬 spec — 계획·조달 계획·서술 규칙의 재료.
     *     null 이면(풀과 spec 목록이 어긋난 경우) 판정에서 얻을 수 있는 것만으로
     *     합성한다: 답의 바닥은 spec 이 아니라 needs 판정이다.
     * @param schemaSource 컬럼 의미 출처(#49) — null 이거나 겹치는 문서가 없으면
     *     서술 규칙에 발췌 절이 생기지 않는다(BE 는 의미를 지어내지 않는다).
     */
    public static List<LlmMessage> messages(List<HistoryMessage> history, QueryScope scope,
            SkillSpec spec, Narration narration, SchemaSource schemaSource) {
        List<LlmMessage> out = new ArrayList<>();
        out.add(LlmMessage.of(Role.SYSTEM, IDENTITY));
        for (HistoryMessage m : history != null ? history : List.<HistoryMessage>of()) {
            out.add(LlmMessage.of(
                    m.role() == Role.ASSISTANT ? Role.ASSISTANT : Role.USER,
                    m.content() != null ? m.content() : ""));
        }
        out.add(LlmMessage.of(Role.ASSISTANT, planTurn(scope, spec, narration)));
        String procurement = procurementTurn(spec);
        if (procurement != null) {
            out.add(LlmMessage.of(Role.ASSISTANT, procurement));
        }
        out.addAll(replay(spec, narration));
        out.add(LlmMessage.of(Role.ASSISTANT, verdictTurn(spec, narration)));
        out.add(LlmMessage.of(Role.SYSTEM,
                narrationRules(spec, schemaExcerpt(narration, schemaSource))));
        out.add(LlmMessage.of(Role.USER, NARRATE_INSTRUCTION));
        return out;
    }

    // ── 계획 ────────────────────────────────────────────────────────────────

    /**
     * 계획 턴 — rephrasing 이 들어가는 자리다. 사용자 발화(A)도 시스템이 내려준
     * 배경(B)도 아닌 <b>모델 자신의 재진술</b>로 둔다: 추론 경로였다면 정확히 그
     * 자리에 그 문장이 있었을 것이고, 그래서 답이 "말씀하신 대로"로 되받지도
     * 않는다(라우팅이 어긋나도 사용자 질문을 갈아치우지 않는다).
     *
     * <p>needs 는 <b>판정 전 전량</b>이다 — 조건부(when)도 조달 수단 없는 것도
     * 함께. 무엇을 알아보려 했는가는 무엇이 채워졌는가와 다른 사실이다.
     */
    static String planTurn(QueryScope scope, SkillSpec spec, Narration narration) {
        List<String> parts = new ArrayList<>();
        parts.add("분석 대상 — " + target(scope, narration) + ".");
        if (spec != null && spec.rephrasing() != null && !spec.rephrasing().isBlank()) {
            parts.add("이 질문에 답한다는 건 다음을 말하는 것이다: " + spec.rephrasing().trim());
        }
        List<String> lines = new ArrayList<>();
        lines.add("그러려면 알아야 할 것:");
        for (String line : needPlanLines(spec, narration)) {
            lines.add("- " + line);
        }
        parts.add(String.join("\n", lines));
        return String.join("\n\n", parts);
    }

    /** need 한 줄 = {@code what (조건) → 채움 자리}. spec 이 없으면 판정에서 what 만 얻는다. */
    private static List<String> needPlanLines(SkillSpec spec, Narration narration) {
        List<String> out = new ArrayList<>();
        if (spec == null || spec.needs() == null || spec.needs().isEmpty()) {
            for (NeedsResolver.NeedStatus n : narration.resolution().needs()) {
                if (n.what() != null && !n.what().isBlank()) {
                    out.add(n.what().trim());
                }
            }
            return out;
        }
        Map<String, String> tables = tablesById(spec);
        for (SkillSpec.SkillNeed need : spec.needs()) {
            StringBuilder line = new StringBuilder(
                    need.what() != null ? need.what().trim() : need.id());
            if (need.when() != null && !need.when().isBlank()) {
                line.append(" (").append(need.when().trim()).append(" 일 때만)");
            }
            line.append(" → ").append(fillTargets(need, tables));
            out.add(line.toString());
        }
        return out;
    }

    /** {@code fdc_sensor.UNIT_CD} — 여럿이면 OR 이므로 "또는"으로 잇는다. */
    private static String fillTargets(SkillSpec.SkillNeed need, Map<String, String> tables) {
        List<String> targets = new ArrayList<>();
        for (SkillSpec.Fill fill : need.fills()) {
            String where = tables.getOrDefault(fill.query(), fill.query());
            targets.add(where + "." + fill.column());
        }
        return targets.isEmpty() ? "조달 수단 없음" : String.join(" 또는 ", targets);
    }

    /** 조달 id → 원천 테이블명. spec 미저작이면 SQL 의 FROM 에서 줍고, 그것도 실패하면 id. */
    private static Map<String, String> tablesById(SkillSpec spec) {
        Map<String, String> out = new LinkedHashMap<>();
        for (SkillSpec.SpecQuery q : spec.queries() != null ? spec.queries()
                : List.<SkillSpec.SpecQuery>of()) {
            String table = q.table() != null && !q.table().isBlank()
                    ? q.table().trim()
                    : SqlRender.tableOf(q.sql());
            out.put(q.id(), table != null ? table : q.id());
        }
        return out;
    }

    /** {@code 설비 CVD-01 · 센서 정보 (fdc-explain-sensor; snsr_id=412086)} */
    private static String target(QueryScope scope, Narration narration) {
        QueryScope.Analysis analysis = matchAnalysis(scope, narration);
        String focus = analysis != null && analysis.focus() != null && !analysis.focus().isBlank()
                ? analysis.focus().trim()
                : narration.skill();
        List<String> detail = new ArrayList<>();
        detail.add(narration.skill());
        for (Map.Entry<String, String> kv : sorted(narration.args()).entrySet()) {
            detail.add(kv.getKey() + "=" + kv.getValue());
        }
        String line = focus + " (" + String.join("; ", detail) + ")";
        if (analysis != null && analysis.equipment() != null && !analysis.equipment().isBlank()) {
            line = "설비 " + analysis.equipment().trim() + " · " + line;
        }
        return line;
    }

    // ── 조달 계획 ───────────────────────────────────────────────────────────

    /**
     * 조달 계획 턴 — 무엇을 어떤 의존으로 얻을 것인가. SQL 은 여기 안 쓴다(실물은
     * act 의 인자에 있다): 계획은 <b>무엇을·왜·언제</b>이고 같은 긴 문자열을 두 번
     * 싣지 않는다. spec 의 {@code notes} 는 저자가 남긴 판단이므로 그대로 옮긴다.
     */
    static String procurementTurn(SkillSpec spec) {
        if (spec == null || spec.queries() == null || spec.queries().isEmpty()) {
            return null;
        }
        Map<String, String> tables = tablesById(spec);
        List<String> lines = new ArrayList<>();
        lines.add("조달 계획 " + spec.queries().size() + "건:");
        for (SkillSpec.SpecQuery q : spec.queries()) {
            StringBuilder line = new StringBuilder("- ")
                    .append(q.id()).append(" (").append(tables.get(q.id())).append(") — ")
                    .append(dependency(q));
            if (q.notes() != null && !q.notes().isBlank()) {
                line.append(' ').append(q.notes().trim());
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    /** 이 조달이 언제 돌 수 있는가 — 인자만 필요하면 즉시, 다른 조달을 물면 그 뒤. */
    private static String dependency(SkillSpec.SpecQuery query) {
        List<String> upstream = new ArrayList<>();
        List<String> args = new ArrayList<>();
        for (SkillSpec.BindSource src : query.bindings().values()) {
            if (src == null) {
                continue;
            }
            if ("query".equals(src.from())) {
                upstream.add(src.query() + "." + src.column());
            } else if (src.arg() != null) {
                args.add(src.arg());
            }
        }
        if (!upstream.isEmpty()) {
            return String.join(", ", upstream) + " 이(가) 있어야 한다.";
        }
        if (!args.isEmpty()) {
            return "인자 " + String.join(", ", args) + " 로 바로 조회할 수 있다.";
        }
        return "바로 조회할 수 있다.";
    }

    // ── act 와 도착 ─────────────────────────────────────────────────────────

    /**
     * 조달 왕복 재생 — 도착한 조달을 <b>바인드 깊이</b>로 묶어 라운드를 만든다.
     * 한 라운드를 한 번의 {@link #TOOL_NAME} 호출로 요청하고, 그 결과를 tool
     * 메시지로 받는다. 라운드 사이에는 가교 문장이 들어간다: 다음 라운드 SQL 에
     * 박힌 값이 <b>어디서 나온 값인지</b>를 남기지 않으면 출처 없는 값이 된다.
     */
    private static List<LlmMessage> replay(SkillSpec spec, Narration narration) {
        List<LlmMessage> out = new ArrayList<>();
        List<List<QueryArrival>> rounds = rounds(narration);
        Map<String, ChatDataSnapshot> arrived = arrivedByKey(narration);
        Set<String> asked = new LinkedHashSet<>();
        for (int r = 0; r < rounds.size(); r++) {
            List<QueryArrival> round = rounds.get(r);
            if (r > 0) {
                out.add(LlmMessage.of(Role.ASSISTANT, bridgeTurn(rounds.get(r - 1), round)));
            }
            String callId = "c" + (r + 1);
            for (QueryArrival a : round) {
                asked.add(a.query().id());
            }
            out.add(LlmMessage.assistantToolCalls(List.of(new LlmToolCall(
                    callId, TOOL_NAME, callArgs(spec, narration, round, arrived, r == 0, asked)))));
            out.add(LlmMessage.toolResult(callId, TOOL_NAME, arrivedJson(round)));
        }
        return out;
    }

    /**
     * 도착한 조달을 바인드 깊이로 묶는다 — 깊이 0 은 인자만으로 도는 것, 깊이 n 은
     * 깊이 n-1 의 결과를 무는 것. 실제 왕복 순서가 그랬고, 판정도 그 순서로 열렸다.
     */
    private static List<List<QueryArrival>> rounds(Narration narration) {
        Map<String, QueryPool.Query> byId = new LinkedHashMap<>();
        for (QueryArrival a : narration.arrivals()) {
            byId.put(a.query().id(), a.query());
        }
        Map<Integer, List<QueryArrival>> byDepth = new TreeMap<>();
        for (QueryArrival a : narration.arrivals()) {
            int depth = depthOf(a.query().id(), byId, new LinkedHashSet<>());
            byDepth.computeIfAbsent(depth, k -> new ArrayList<>()).add(a);
        }
        return new ArrayList<>(byDepth.values());
    }

    private static int depthOf(
            String id, Map<String, QueryPool.Query> byId, Set<String> onPath) {
        QueryPool.Query query = byId.get(id);
        if (query == null || !onPath.add(id)) {
            return 0; // 도착하지 않은 상류(다른 경로로 채워진 경우)·순환은 뿌리로 본다.
        }
        int depth = 0;
        for (SkillSpec.BindSource src : query.binds().values()) {
            if (src != null && "query".equals(src.from())) {
                depth = Math.max(depth, 1 + depthOf(src.query(), byId, onPath));
            }
        }
        return depth;
    }

    /** 라운드 사이 가교 — 다음 SQL 에 박힐 값이 앞 라운드의 어느 값인지. */
    private static String bridgeTurn(List<QueryArrival> previous, List<QueryArrival> next) {
        List<String> from = new ArrayList<>();
        for (QueryArrival a : previous) {
            from.add(a.query().id());
        }
        List<String> to = new ArrayList<>();
        for (QueryArrival a : next) {
            to.add(a.query().id());
        }
        return String.join(", ", from) + " 도착. 그 값으로 " + String.join(", ", to)
                + " 를 이어서 조회한다.";
    }

    /**
     * {@link #TOOL_NAME} 인자 — {@code needs}(무엇을 알아야 하나) + {@code queries}
     * (어떻게 얻나). 첫 라운드에는 <b>아직 못 여는 need 까지</b> 실어 보낸다: 사용자가
     * 그 사실을 이미 다른 방식으로 갖고 있으면 우리가 정한 순서를 기다릴 이유가 없다.
     */
    private static Map<String, Object> callArgs(
            SkillSpec spec, Narration narration, List<QueryArrival> round,
            Map<String, ChatDataSnapshot> arrived, boolean first, Set<String> asked) {
        Set<String> inRound = new LinkedHashSet<>();
        for (QueryArrival a : round) {
            inRound.add(a.query().id());
        }
        Map<String, String> tables = spec != null ? tablesById(spec) : Map.of();

        List<Map<String, Object>> needs = new ArrayList<>();
        for (SkillSpec.SkillNeed need : spec != null && spec.needs() != null ? spec.needs()
                : List.<SkillSpec.SkillNeed>of()) {
            List<SkillSpec.Fill> fills = need.fills();
            boolean here = fills.stream().anyMatch(f -> inRound.contains(f.query()));
            if (!here && !first) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", need.id());
            entry.put("what", need.what());
            if (!fills.isEmpty()) {
                SkillSpec.Fill fill = fills.get(0);
                entry.put("fill", Map.of("query", fill.query(), "column", fill.column()));
            }
            if (!here) {
                entry.put("blocked", fills.isEmpty()
                        ? "조달 수단 없음 — 다른 방식으로 제공 가능"
                        : upstreamOf(spec, fills.get(0).query()) + " 필요");
            }
            needs.add(entry);
        }

        List<Map<String, Object>> queries = new ArrayList<>();
        for (QueryArrival a : round) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", a.query().id());
            entry.put("table", a.query().table() != null
                    ? a.query().table() : tables.getOrDefault(a.query().id(), a.query().id()));
            entry.put("sql", renderedSql(a.query(), narration, arrived));
            queries.add(entry);
        }

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("needs", needs);
        args.put("queries", queries);
        return args;
    }

    /** 그 조달을 막고 있는 상류 표기 — {@code sensor_row.EQP_ID}. 없으면 조달 id. */
    private static String upstreamOf(SkillSpec spec, String queryId) {
        for (SkillSpec.SpecQuery q : spec.queries() != null ? spec.queries()
                : List.<SkillSpec.SpecQuery>of()) {
            if (!q.id().equals(queryId)) {
                continue;
            }
            for (SkillSpec.BindSource src : q.bindings().values()) {
                if (src != null && "query".equals(src.from())) {
                    return src.query() + "." + src.column();
                }
            }
        }
        return queryId;
    }

    /** 카드에 찍혔던 것과 같은 SQL. 바인드를 못 풀면(행 미수신) 원문 그대로 둔다. */
    private static String renderedSql(
            QueryPool.Query query, Narration narration, Map<String, ChatDataSnapshot> arrived) {
        BindOutcome outcome = BindResolver.resolve(
                query, narration.args(), null, arrived::get);
        if (!(outcome instanceof BindOutcome.Ready ready)) {
            return query.sql();
        }
        try {
            return SqlRender.render(query.sql(), ready.binds());
        } catch (RuntimeException e) {
            // 로드 검증이 걸렀어야 할 배선 불일치 — 서술을 죽이지 않고 원문을 둔다.
            return query.sql();
        }
    }

    private static Map<String, ChatDataSnapshot> arrivedByKey(Narration narration) {
        Map<String, ChatDataSnapshot> out = new LinkedHashMap<>();
        for (QueryArrival a : narration.arrivals()) {
            if (a.hit() != null && a.hit().queryKey() != null && a.full() != null) {
                out.put(a.hit().queryKey(), a.full());
            }
        }
        return out;
    }

    /**
     * tool 메시지 본문 — 도착한 것 전부. {@code source} 를 항목마다 남기는 것이
     * 계약의 핵심이다: 요청한 조달로 왔는지 다른 경로로 왔는지를 판정과 서술이
     * 인용할 수 있어야 한다.
     */
    private static String arrivedJson(List<QueryArrival> round) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (QueryArrival a : round) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", a.query().id());
            if (a.query().table() != null) {
                item.put("table", a.query().table());
            }
            ChatDataSnapshot full = a.full();
            if (a.hit().isEmptyResult()) {
                item.put("columns", arrivedColumns(a));
                item.put("rows", List.of());
            } else if (full == null || !full.hasRows()) {
                // 판정 집합에는 도착으로 잡혔는데 rows 실물이 이 요청에 안 실렸다 —
                // 값을 지어내지 않고 없는 사실을 적는다.
                item.put("rowCount", a.hit().rowCount());
                item.put("note", "행 전문이 이 요청에 실리지 않았다 — 값은 데이터 패널에 있다.");
            } else {
                item.put("columns", arrivedColumns(a));
                item.put("rows", full.rows());
            }
            items.add(item);
        }
        return stringify(Map.of("arrived", items));
    }

    private static List<String> columnsOf(QueryArrival arrival) {
        List<String> columns = arrival.hit().columns();
        return columns != null ? columns : List.of();
    }

    /**
     * 이 도착이 tool 메시지에 실제로 실은 컬럼 — 0행이면 스키마만(값은 없어도 열은
     * 안다), rows 실물이 이 요청에 안 실렸으면 하나도 없다({@link #arrivedJson} 이
     * 그 경우 columns 자체를 안 싣는 것과 같은 판단). {@link #schemaExcerpt} 의
     * 재료도 여기서 나온다 — 응답에 실리지 않은 컬럼의 의미가 섞여 들어갈 길이 없다.
     */
    private static List<String> arrivedColumns(QueryArrival a) {
        if (a.hit().isEmptyResult()) {
            return columnsOf(a);
        }
        ChatDataSnapshot full = a.full();
        if (full == null || !full.hasRows()) {
            return List.of();
        }
        return full.columns() != null ? full.columns() : columnsOf(a);
    }

    // ── 판정 ────────────────────────────────────────────────────────────────

    /**
     * 판정 턴 — 결정론 판정({@link NeedsResolver})의 결과를 모델의 관찰로 되돌려
     * 놓는다. <b>못 찬 것을 한 덩이로 뭉치지 않는다</b>: 0행으로 없음이 확인된 것과
     * 조달 수단이 아예 없는 것과 조건 판정이 막힌 것은 서술이 서로 다른 문장이어야
     * 한다("없습니다" / "확인할 방법이 없습니다" / "판단 근거가 없습니다").
     */
    static String verdictTurn(SkillSpec spec, Narration narration) {
        NeedsResolver.Resolution resolution = narration.resolution();
        Set<String> emptyArrived = emptyArrivals(narration);
        Map<String, SkillSpec.SkillNeed> needsById = needsById(spec);

        List<String> filled = new ArrayList<>();
        List<String> confirmedEmpty = new ArrayList<>();
        List<String> notArrived = new ArrayList<>();
        List<String> unprocurable = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<String> inactive = new ArrayList<>();

        Set<String> replayed = replayedQueries(narration);
        for (NeedsResolver.NeedStatus need : resolution.needs()) {
            String what = need.what() != null && !need.what().isBlank() ? need.what() : need.id();
            // 「0행으로 도착」과 「조달 수단이 없음」은 판정 상태가 같다(UNPROCURABLE =
            // filledBy 가 비었거나 지목한 조달이 전부 빈손). 서술은 갈려야 하므로
            // ("없습니다" vs "확인할 방법이 없습니다") 도착 사실로 여기서 가른다.
            boolean empty = touchesEmpty(needsById.get(need.id()), emptyArrived);
            switch (need.state()) {
                case FILLED -> filled.add(offReplay(need, needsById.get(need.id()), replayed)
                        ? what + " = " + need.value()
                        : what);
                case UNFILLED -> (empty ? confirmedEmpty : notArrived).add(what);
                case UNPROCURABLE -> (empty ? confirmedEmpty : unprocurable).add(what);
                case PENDING_GATE -> pending.add(what);
                case INACTIVE -> inactive.add(what);
                default -> { }
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("확인 결과:");
        bucket(lines, "확보", filled);
        bucket(lines, "조회했으나 없음이 확인됨", confirmedEmpty);
        bucket(lines, "도착하지 않음", notArrived);
        bucket(lines, "조달 수단이 없어 확인 불가", unprocurable);
        bucket(lines, "선행 값이 정해지지 않아 다루지 못함", pending);
        bucket(lines, "해당 없음", inactive);
        lines.add("더 조달할 것은 없다.");
        return String.join("\n", lines);
    }

    /**
     * 이 사실이 <b>재생된 조달 밖에서</b> 왔는가 — 지목한 조달이 하나도 도착하지 않았는데
     * 찼다면 다른 경로로 온 것이다(채움 폭포의 2·3차).
     *
     * <p>그때는 값을 함께 적는다. 안 그러면 모델은 tool 결과 어디에도 없는 사실을
     * "확보됐다"는 말만 듣고 서술해야 하고, 그 자리가 정확히 지어내기가 나는 자리다.
     * 값을 못 읽은 경우(경량 판정)는 이름만 적는다 — 없는 값을 적을 수는 없다.
     */
    private static boolean offReplay(
            NeedsResolver.NeedStatus need, SkillSpec.SkillNeed spec, Set<String> replayed) {
        if (need.value() == null || need.value().isBlank()) {
            return false;
        }
        if (spec == null || spec.fills().isEmpty()) {
            return true; // 조달 수단이 없는 need 가 찼다 = 밖에서 온 것이다.
        }
        for (SkillSpec.Fill fill : spec.fills()) {
            if (fill != null && replayed.contains(fill.query())) {
                return false;
            }
        }
        return true;
    }

    /** 이 서술에 재생된 조달들 — tool 결과로 모델이 실제로 본 것. */
    private static Set<String> replayedQueries(Narration narration) {
        Set<String> out = new LinkedHashSet<>();
        for (QueryArrival arrival : narration.arrivals()) {
            out.add(arrival.query().id());
        }
        return out;
    }

    private static void bucket(List<String> lines, String head, List<String> items) {
        if (!items.isEmpty()) {
            lines.add("- " + head + ": " + String.join(" / ", items));
        }
    }

    /** 0행으로 도착이 확정된 조달 id. */
    private static Set<String> emptyArrivals(Narration narration) {
        Set<String> out = new LinkedHashSet<>();
        for (QueryArrival a : narration.arrivals()) {
            if (a.hit().isEmptyResult()) {
                out.add(a.query().id());
            }
        }
        return out;
    }

    /** 이 need 의 조달 중 하나라도 0행으로 확정됐는가 — "없음 확인"과 "미도착"의 갈림. */
    private static boolean touchesEmpty(SkillSpec.SkillNeed need, Set<String> emptyArrived) {
        if (need == null) {
            return false;
        }
        return need.fills().stream().anyMatch(f -> emptyArrived.contains(f.query()));
    }

    private static Map<String, SkillSpec.SkillNeed> needsById(SkillSpec spec) {
        Map<String, SkillSpec.SkillNeed> out = new LinkedHashMap<>();
        for (SkillSpec.SkillNeed need : spec != null && spec.needs() != null ? spec.needs()
                : List.<SkillSpec.SkillNeed>of()) {
            out.put(need.id(), need);
        }
        return out;
    }

    // ── 서술 규칙 ───────────────────────────────────────────────────────────

    /**
     * 서술 규칙 — 4 단계 밖의 별개 축이라 system 이다. 계획(무엇을 알아야 하나)도
     * 판정(무엇이 찼나)도 아닌, <b>어떻게 쓸 것인가</b>만 여기 있다. 컬럼 의미(#49)도
     * 도착한 데이터를 어떻게 읽을 것인가이므로 별도 턴이 아니라 이 축의 절 하나로
     * 얹는다.
     *
     * @param schemaExcerpt {@link #schemaExcerpt} 가 만든 절 — 없으면(null) 안 실린다.
     */
    static String narrationRules(SkillSpec spec, String schemaExcerpt) {
        List<String> parts = new ArrayList<>();
        parts.add(NO_INVENTION);
        if (spec != null && spec.output() != null && spec.output().avoid() != null
                && !spec.output().avoid().isEmpty()) {
            List<String> lines = new ArrayList<>();
            lines.add("하지 말 것:");
            for (String avoid : spec.output().avoid()) {
                lines.add("- " + avoid);
            }
            parts.add(String.join("\n", lines));
        }
        if (schemaExcerpt != null && !schemaExcerpt.isBlank()) {
            parts.add(schemaExcerpt);
        }
        return String.join("\n\n", parts);
    }

    /**
     * 컬럼 의미 발췌 — 이 호출의 {@link #replay} 가 <b>실제로 실은</b> (테이블, 컬럼)
     * 에서만 유도한다({@link #arrivedColumns}). 조달 계획이나 spec 선언이 아니라
     * 응답에 실제로 담긴 것만 보므로 데이터와 설명이 어긋날 수 없다.
     *
     * <p>호출 내 중복은 {@code (테이블, 컬럼)} 키 union 으로 막는다(동명 컬럼이
     * 테이블마다 뜻이 다를 수 있어 컬럼명 단독 키는 못 쓴다). 호출 간 중복은 별도
     * 방어가 필요 없다 — {@link #messages} 의 히스토리 재생이 이전 턴의 SYSTEM 메시지
     * 를 다시 넣지 않는다.
     *
     * @return schemaSource 가 없거나 겹치는 문서가 없으면 null.
     */
    private static String schemaExcerpt(Narration narration, SchemaSource schemaSource) {
        if (schemaSource == null || narration == null) {
            return null;
        }
        Map<String, Set<String>> columnsByTable = new TreeMap<>();
        for (QueryArrival a : narration.arrivals()) {
            String table = a.query().table();
            if (table == null || table.isBlank()) {
                continue;
            }
            for (String column : arrivedColumns(a)) {
                columnsByTable.computeIfAbsent(table, t -> new TreeSet<>()).add(column);
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : columnsByTable.entrySet()) {
            Optional<SchemaDoc> doc = schemaSource.byTable(e.getKey());
            if (doc.isEmpty()) {
                continue;
            }
            List<String> colLines = schemaColumnLines(doc.get(), e.getValue());
            if (colLines.isEmpty()) {
                continue;
            }
            String comment = doc.get().tableComment();
            lines.add(e.getKey() + (comment != null && !comment.isBlank()
                    ? " — " + comment.trim() : ""));
            lines.addAll(colLines);
        }
        if (lines.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add("데이터 컬럼의 의미:");
        parts.addAll(lines);
        return String.join("\n", parts);
    }

    /** {@code - SNSR_VAL: 센서 측정값.} — 설명이 없는 컬럼은 조용히 빠진다(지어낼 게 없다). */
    private static List<String> schemaColumnLines(SchemaDoc doc, Set<String> columns) {
        List<String> out = new ArrayList<>();
        Map<String, String> descs = doc.columnDescs() != null ? doc.columnDescs() : Map.of();
        for (String column : columns) {
            String desc = descs.get(column);
            if (desc != null && !desc.isBlank()) {
                out.add("- " + column + ": " + desc.trim());
            }
        }
        return out;
    }

    // ── 공용 ────────────────────────────────────────────────────────────────

    /** scope 의 분석 중 이 run 과 같은 것 — 스킬이 같고 조회 키가 어긋나지 않는 첫 항목. */
    private static QueryScope.Analysis matchAnalysis(QueryScope scope, Narration narration) {
        if (scope == null || scope.analyses() == null) {
            return null;
        }
        for (QueryScope.Analysis a : scope.analyses()) {
            if (a == null || a.skill() == null || !a.skill().trim().equals(narration.skill())) {
                continue;
            }
            boolean argsAgree = true;
            for (Map.Entry<String, String> kv
                    : (a.inputs() != null ? a.inputs() : Map.<String, String>of()).entrySet()) {
                if (kv.getValue() == null || kv.getValue().isBlank()) {
                    continue;
                }
                if (!kv.getValue().trim().equals(narration.args().get(kv.getKey()))) {
                    argsAgree = false;
                    break;
                }
            }
            if (argsAgree) {
                return a;
            }
        }
        return null;
    }

    /** 인자 순서를 키 정렬로 고정 — 같은 run 은 같은 문장이 나온다(순수 함수 전제). */
    private static Map<String, String> sorted(Map<String, String> args) {
        return args != null ? new TreeMap<>(args) : Map.of();
    }

    private static String stringify(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}

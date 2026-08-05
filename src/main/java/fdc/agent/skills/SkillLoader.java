package fdc.agent.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.AgentTool;
import fdc.agent.chat.AgentTool.ToolResult;
import fdc.agent.contract.ChatTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * skill-loader — domain-skill spec(v3)을 에이전트 툴로 컴파일. 하이브리드(C) 모델:
 * 조달은 코드가 결정론으로 실행하고, 출력 지침(서술 지시·반드시 포함·avoid·examples)은
 * 번역 없이 프로즈 그대로 결과에 실어 LLM 이 자연어 서술에 참고하게 한다. LLM 은
 * SQL 을 보지 않는다.
 *
 * <p><b>실행 순서가 spec 에서 사라졌다.</b> v2 는 {@code steps[]} 를 위에서 아래로
 * 돌렸지만 v3 의 {@code queries[]} 는 카탈로그다. 대신 <b>무엇이 도는지는 유도된다</b>:
 * 알아야 할 것 중 아직 못 채운 것이 지목한 조달만 돌고, 한 바퀴 돌 때마다 다시
 * 판정한다({@link NeedsResolver}). 갈림형 분기가 코드에서 사라진 것도 이것 때문이다 —
 * {@code sensor_kind = PHYSICAL} 일 때만 활성인 need 는 물리 센서일 때만 자기 조달을
 * 부른다. 가상 센서 쪽 조회는 아예 돌지 않으므로 "0행"으로 오해될 일도 없다.
 *
 * <p>{@code description} 은 spec 필드가 아니라 여기서 합성된다 — 합성 결과를 공유
 * 픽스처로 묶지 않고 소비자가 각자 만들기로 한 결정이라(foundry 설계 §4-1), 아래
 * 골격은 akg {@code src/render/domain-skill.mjs} 의 것을 그대로 옮긴 것이다.
 * 골격이 바뀌면 같이 고칠 것.
 */
public final class SkillLoader {
    private SkillLoader() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static AgentTool loadSkill(SkillSpec spec, SkillQuery query) {
        validateSpec(spec);
        String toolName = spec.name().replace("-", "_");
        Map<String, Object> properties = new LinkedHashMap<>();
        for (SkillSpec.SkillInput p : spec.inputs()) {
            properties.put(p.name(), Map.of("type", "string", "description", p.description()));
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", spec.inputs().stream()
                .filter(SkillSpec.SkillInput::required)
                .map(SkillSpec.SkillInput::name)
                .toList());

        // 스킬은 guidance 를 내지 않는다 — 합성된 description 이 이미 "언제 부르나"다.
        return AgentTool.of(
                toolName,
                synthesizeDescription(spec),
                parameters,
                args -> runSkill(spec, query, args));
    }

    private static final Pattern BIND_VAR = Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)");

    /**
     * 로드 시 spec 검증 — akg {@code envelope.mjs} 시맨틱 체크와 같은 것을 본다.
     * 실행기의 책임은 배선의 저작이 아니라 <b>나쁜 선언의 거부</b>다: 어긋난 spec 은
     * 런타임 침묵 스킵이 아니라 기동 실패로 드러난다(이슈 #7 ②의 이름-매칭 사고 방지).
     *
     * <p>v3 에서 볼 것이 늘었다 — 배선(인자 실재·대상 조달 실재·{@code :var} 정확
     * 일치·순환) 위에 <b>needs</b> 가 얹혔다: {@code when} 이 가리키는 need 가 있는가,
     * 순환하지 않는가, {@code filledBy} 의 컬럼이 그 조달의 SELECT 목록에 있는가.
     * 마지막 것이 "컬럼까지 못 박는다"는 결정의 안전망이다 — SQL 을 고치고 spec 을
     * 안 고치는 사고를 여기서 잡는다.
     *
     * <p>public 인 이유: {@link AkgSkillSource} 가 허브에서 받은 spec 을 수용 전에
     * 같은 검증으로 거른다 — 나쁜 문서 하나가 챗을 못 죽이게.
     */
    public static void validateSpec(SkillSpec spec) {
        Set<String> inputNames = new HashSet<>();
        for (SkillSpec.SkillInput p : spec.inputs()) {
            inputNames.add(p.name());
        }

        Map<String, SkillSpec.SpecQuery> byId = new LinkedHashMap<>();
        for (SkillSpec.SpecQuery query : spec.queries()) {
            if (query.id() == null || query.id().isBlank()) {
                throw new IllegalStateException(spec.name() + ": queries[] 에 id 가 없습니다");
            }
            if (byId.putIfAbsent(query.id(), query) != null) {
                throw new IllegalStateException(spec.name() + ": 조달 id 가 중복입니다: " + query.id());
            }
        }

        Map<String, List<String>> queryEdges = new LinkedHashMap<>();
        for (SkillSpec.SpecQuery query : spec.queries()) {
            Map<String, SkillSpec.BindSource> binds = query.bindings();
            Set<String> sqlVars = extractBindVars(query.sql());
            List<String> deps = new ArrayList<>();
            for (Map.Entry<String, SkillSpec.BindSource> e : binds.entrySet()) {
                SkillSpec.BindSource src = e.getValue();
                String at = spec.name() + " queries[" + query.id() + "].binds." + e.getKey();
                if ("arg".equals(src.from())) {
                    if (!inputNames.contains(src.arg())) {
                        throw new IllegalStateException(at + ": no input named \"" + src.arg() + "\"");
                    }
                } else if ("query".equals(src.from())) {
                    if (src.query() == null || !byId.containsKey(src.query())) {
                        throw new IllegalStateException(at + ": no query named \"" + src.query() + "\"");
                    }
                    if (src.query().equals(query.id())) {
                        throw new IllegalStateException(at + ": 자기 자신을 참조합니다");
                    }
                    deps.add(src.query());
                } else {
                    throw new IllegalStateException(at + ": unknown from \"" + src.from() + "\"");
                }
                if (!sqlVars.contains(e.getKey())) {
                    throw new IllegalStateException(at + ": declared but sql has no :" + e.getKey());
                }
            }
            for (String v : sqlVars) {
                if (!binds.containsKey(v)) {
                    throw new IllegalStateException(spec.name() + " queries[" + query.id()
                            + "]: sql uses :" + v + " but binds does not declare it");
                }
            }
            queryEdges.put(query.id(), deps);
        }
        String queryCycle = firstCycle(queryEdges);
        if (queryCycle != null) {
            throw new IllegalStateException(spec.name() + ": 조달 배선이 순환합니다: " + queryCycle);
        }

        Set<String> needIds = new LinkedHashSet<>();
        for (SkillSpec.SkillNeed need : spec.needs()) {
            if (need.id() == null || need.id().isBlank()) {
                throw new IllegalStateException(spec.name() + ": needs[] 에 id 가 없습니다");
            }
            if (!needIds.add(need.id())) {
                throw new IllegalStateException(spec.name() + ": need id 가 중복입니다: " + need.id());
            }
        }
        Map<String, List<String>> needEdges = new LinkedHashMap<>();
        for (SkillSpec.SkillNeed need : spec.needs()) {
            String at = spec.name() + " needs[" + need.id() + "]";
            List<String> deps = new ArrayList<>();
            if (need.when() != null && !need.when().isBlank()) {
                NeedsResolver.Gate gate = NeedsResolver.Gate.parse(need.when());
                if (gate == null) {
                    throw new IllegalStateException(at + ".when: 조건식으로 못 읽습니다: " + need.when());
                }
                if (!needIds.contains(gate.needId())) {
                    throw new IllegalStateException(at + ".when: no need named \"" + gate.needId() + "\"");
                }
                deps.add(gate.needId());
            }
            for (SkillSpec.Fill fill : need.fills()) {
                SkillSpec.SpecQuery target = byId.get(fill.query());
                if (target == null) {
                    throw new IllegalStateException(at + ".filledBy: no query named \""
                            + fill.query() + "\"");
                }
                List<String> columns = SqlRender.columnsOf(target.sql());
                if (columns != null && columns.stream().noneMatch(c -> c.equalsIgnoreCase(fill.column()))) {
                    throw new IllegalStateException(at + ".filledBy: " + fill.query()
                            + " 의 SELECT 목록에 " + fill.column() + " 이 없습니다");
                }
            }
            needEdges.put(need.id(), deps);
        }
        String needCycle = firstCycle(needEdges);
        if (needCycle != null) {
            throw new IllegalStateException(spec.name() + ": needs 의 when 이 순환합니다: " + needCycle);
        }
    }

    /** 순환이 있으면 그 경로를 사람이 읽을 문자열로, 없으면 null. */
    private static String firstCycle(Map<String, List<String>> edges) {
        Set<String> done = new HashSet<>();
        for (String start : edges.keySet()) {
            List<String> path = new ArrayList<>();
            String cycle = walk(start, edges, new LinkedHashSet<>(), done, path);
            if (cycle != null) {
                return cycle;
            }
        }
        return null;
    }

    private static String walk(String node, Map<String, List<String>> edges,
            Set<String> onPath, Set<String> done, List<String> path) {
        if (done.contains(node)) {
            return null;
        }
        if (!onPath.add(node)) {
            return String.join(" → ", path) + " → " + node;
        }
        path.add(node);
        for (String next : edges.getOrDefault(node, List.of())) {
            String cycle = walk(next, edges, onPath, done, path);
            if (cycle != null) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        onPath.remove(node);
        done.add(node);
        return null;
    }

    /** SQL 의 {@code :bind} 변수 추출 — 따옴표 리터럴은 벗긴다(날짜 마스크 ':' 오탐 방지). */
    private static Set<String> extractBindVars(String sql) {
        Set<String> vars = new HashSet<>();
        Matcher m = BIND_VAR.matcher(sql.replaceAll("'[^']*'", "''"));
        while (m.find()) {
            vars.add(m.group(1));
        }
        return vars;
    }

    /**
     * 라우팅 문장 합성 — 사용자가 실제로 던지는 말을 <b>그대로 인용</b>한다. v2 는
     * {@code scope.의도}+{@code focus} 로 골격을 조립했는데, 그건 분류 어휘라 사람이
     * 묻는 말과 닮은 데가 없었다. 인용이 곧 라우팅 신호다.
     *
     * <p>골격은 akg 렌더러와 같아야 한다 — 갈리면 허브가 만든 SKILL.md 와 여기서 만든
     * 툴 설명이 서로 다른 문장으로 라우팅한다.
     */
    public static String synthesizeDescription(SkillSpec spec) {
        String asked = spec.questions().stream().map(q -> "\"" + q + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        String when = spec.inputs().stream()
                .filter(SkillSpec.SkillInput::required)
                .map(SkillSpec.SkillInput::name)
                .collect(java.util.stream.Collectors.joining("·")) + " 필요";
        return asked + " 같은 질문에 답한다 (" + when + ").";
    }

    /**
     * 조달 실행 — <b>필요 → 조달 → 재판정</b>을 더 돌 것이 없을 때까지 반복한다.
     * 한 바퀴에 돌 수 있는 것을 다 돌리고 다시 판정하는 이유는, 방금 도착한 값이
     * 다른 need 의 게이트를 열 수 있기 때문이다(물리로 판명되면 그때부터 VID 가
     * 필요해진다).
     *
     * <p>바인드를 못 푼 조달은 이번 바퀴에서 건너뛴다 — 다음 바퀴에 앞 조달이
     * 도착해 있으면 풀린다. 한 바퀴에 아무것도 못 돌면 끝이다(더 이상 진전 없음).
     */
    private static ToolResult runSkill(SkillSpec spec, SkillQuery query, Map<String, Object> args) {
        Map<String, SkillSpec.SpecQuery> byId = new LinkedHashMap<>();
        for (SkillSpec.SpecQuery q : spec.queries()) {
            byId.putIfAbsent(q.id(), q);
        }

        Map<String, Map<String, SkillSpec.BindSource>> wiring = new LinkedHashMap<>();
        for (SkillSpec.SpecQuery q : spec.queries()) {
            wiring.putIfAbsent(q.id(), q.bindings());
        }

        Map<String, List<Map<String, Object>>> results = new LinkedHashMap<>();
        NeedsResolver.Resolution resolution;
        while (true) {
            NeedsResolver.Rows rows = rowsOf(results);
            resolution = NeedsResolver.resolve(
                    spec.needs(), rows, NeedsResolver.reachOf(wiring, rows));
            boolean ran = false;
            for (String id : resolution.wanted()) {
                SkillSpec.SpecQuery q = byId.get(id);
                if (q == null || results.containsKey(id)) {
                    continue;
                }
                Map<String, Object> binds = bindsFor(q, args, results);
                if (binds == null) {
                    continue; // 아직 못 푼다 — 다음 바퀴에 앞 조달이 와 있을 수 있다.
                }
                List<Map<String, Object>> fetched = query.query(q.sql(), binds);
                results.put(id, fetched != null ? fetched : List.of());
                ran = true;
            }
            if (!ran) {
                break;
            }
        }
        return buildResult(spec, results, resolution);
    }

    /** 판정기가 실행 결과를 읽는 창구 — 안 돈 조달은 미도착({@code null})이다. */
    private static NeedsResolver.Rows rowsOf(Map<String, List<Map<String, Object>>> results) {
        return (queryId, column) -> {
            List<Map<String, Object>> rows = results.get(queryId);
            if (rows == null) {
                return null;
            }
            return NeedsResolver.Cell.of(valuesOf(rows, column));
        };
    }

    /** 한 컬럼의 값들(중복·공백 제거). 컬럼 이름은 대소문자를 가리지 않는다. */
    private static List<String> valuesOf(List<Map<String, Object>> rows, String column) {
        Set<String> values = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> cell : row.entrySet()) {
                if (cell.getKey() != null && cell.getKey().trim().equalsIgnoreCase(column.trim())
                        && cell.getValue() != null && !String.valueOf(cell.getValue()).isBlank()) {
                    values.add(String.valueOf(cell.getValue()).trim());
                }
            }
        }
        return List.copyOf(values);
    }

    /** 이 조달의 바인드 값 — 하나라도 못 채우면 null(이번 바퀴에는 못 돈다). */
    private static Map<String, Object> bindsFor(SkillSpec.SpecQuery query,
            Map<String, Object> args, Map<String, List<Map<String, Object>>> results) {
        Map<String, Object> binds = new LinkedHashMap<>();
        for (Map.Entry<String, SkillSpec.BindSource> e : query.bindings().entrySet()) {
            SkillSpec.BindSource src = e.getValue();
            if ("arg".equals(src.from())) {
                Object raw = args.get(src.arg());
                String value = raw == null ? "" : String.valueOf(raw).trim();
                if (value.isEmpty()) {
                    return null;
                }
                binds.put(e.getKey(), value);
                continue;
            }
            List<Map<String, Object>> rows = results.get(src.query());
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            List<String> values = valuesOf(rows, src.column());
            // 갈림길은 고르지 않는다 — 하나로 정해질 때만 이어간다(BE #50).
            if (values.size() != 1) {
                return null;
            }
            binds.put(e.getKey(), values.get(0));
        }
        return binds;
    }

    /**
     * 조회 사실 + 판정 + 출력 지침(프로즈)을 LLM 서술용으로 조립 + 표.
     *
     * <p>v3: <b>반드시 포함</b>이 조회 산출물이 아니라 채워진 need 다. 못 채운 것도
     * 같이 싣는다 — 비어 있다는 사실을 알려 주지 않으면 모델은 그 자리를 그럴듯하게
     * 메운다. 값 의미(코드표)는 스킬에 없다: 표준 db-schema 문서 fetch 경로는
     * 후속 과제(foundry 설계 §13).
     */
    private static ToolResult buildResult(SkillSpec spec,
            Map<String, List<Map<String, Object>>> results, NeedsResolver.Resolution resolution) {
        Set<String> wantedButUnrun = new LinkedHashSet<>();
        for (SkillSpec.SkillNeed need : spec.needs()) {
            NeedsResolver.NeedStatus status = statusOf(resolution, need.id());
            if (status == null || status.state() == NeedsResolver.State.INACTIVE
                    || status.state() == NeedsResolver.State.FILLED) {
                continue;
            }
            for (SkillSpec.Fill fill : need.fills()) {
                if (!results.containsKey(fill.query())) {
                    wantedButUnrun.add(fill.query());
                }
            }
        }

        List<String> parts = new ArrayList<>();
        parts.add("[조회 결과]");
        for (SkillSpec.SpecQuery query : spec.queries()) {
            parts.add("- " + query.id() + ": " + describe(query.id(), results, wantedButUnrun));
        }

        List<String> met = whats(resolution.in(NeedsResolver.State.FILLED));
        if (!met.isEmpty()) {
            parts.add("[알아낸 것]");
            for (String what : met) {
                parts.add("- " + what);
            }
        }
        List<String> unmet = whats(resolution.unmet());
        if (!unmet.isEmpty()) {
            parts.add("[확인되지 않은 것 — 지어내지 말고 확인되지 않았다고 적는다]");
            for (String what : unmet) {
                parts.add("- " + what);
            }
        }

        parts.add("[출력 지침]");
        parts.add("조회한 데이터로 다음 질문에 답한다: " + spec.rephrasing());
        parts.add("정해진 형식은 없다. 체계적·논리적으로, 없는 정보는 지어내지 않는다.");
        if (!met.isEmpty()) {
            parts.add("반드시 포함 (질문이 특정 항목만 묻는 게 아니면): " + String.join(" · ", met));
        }

        parts.add("[하지 말 것]");
        for (String a : spec.output().avoid()) {
            parts.add("- " + a);
        }

        parts.add("[예시 — 모양만 참고, 값은 조회 결과로 바꾼다]");
        for (SkillSpec.SkillExample ex : spec.output().examples()) {
            parts.add("질문: " + ex.ask());
            parts.add("답: " + ex.answer());
        }

        List<ChatTable> tables = new ArrayList<>();
        for (SkillSpec.SpecQuery query : spec.queries()) {
            List<Map<String, Object>> rows = results.get(query.id());
            if (rows != null && !rows.isEmpty()) {
                tables.add(rowsToTable(query.id(), rows));
            }
        }
        return new ToolResult(String.join("\n", parts), tables.isEmpty() ? null : tables);
    }

    private static NeedsResolver.NeedStatus statusOf(
            NeedsResolver.Resolution resolution, String needId) {
        return resolution.needs().stream()
                .filter(n -> n.id().equals(needId))
                .findFirst()
                .orElse(null);
    }

    private static List<String> whats(List<NeedsResolver.NeedStatus> needs) {
        return needs.stream()
                .map(NeedsResolver.NeedStatus::what)
                .filter(w -> w != null && !w.isBlank())
                .toList();
    }

    /**
     * 없음의 <b>세 종류</b>를 갈라 적는다 — 조회한 0행인가, 필요했는데 못 돌린
     * 것인가, 이번 질문에 필요하지 않아 안 돈 것인가. 뭉개면 모델은 안 돈 조회를
     * "그 데이터는 없다"로 단정한다.
     */
    private static String describe(String queryId,
            Map<String, List<Map<String, Object>>> results, Set<String> wantedButUnrun) {
        List<Map<String, Object>> rows = results.get(queryId);
        if (rows == null) {
            return wantedButUnrun.contains(queryId)
                    ? "(앞 조달 결과가 없어 조회하지 못함 — 데이터가 없다는 뜻이 아니다)"
                    : "(이번 질문에는 필요하지 않아 조회하지 않음)";
        }
        return rows.isEmpty() ? "(조회 결과 0행)" : stringify(rows);
    }

    private static ChatTable rowsToTable(String title, List<Map<String, Object>> rows) {
        List<String> columns = rows.isEmpty() ? List.of() : List.copyOf(rows.get(0).keySet());
        return new ChatTable(title, columns, rows);
    }

    private static String stringify(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}

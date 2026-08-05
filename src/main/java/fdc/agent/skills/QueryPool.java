package fdc.agent.skills;

import fdc.agent.util.Js;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 요청 가능한 조달 수단의 <b>닫힌 목록</b> — 로드된 스킬 spec 들의 {@code queries[]} 를
 * 평면화한 것. 발산을 막는 것이 이 유한성이다(임의 깊이 캡이 필요 없는 이유).
 *
 * <p>풀은 새로 만든 개념이 아니라 <b>이미 있던 것을 꺼낸 것</b>이다. 스킬 실행 경로는
 * 처음부터 spec 의 SQL 만 돌렸고 LLM 은 SQL 을 본 적이 없다(메뉴판 방식).
 * 조달 요청만 자유 저작이었다 — 이 클래스는 그 두 갈래를 같은 목록 위에 세운다.
 * 출처가 akg 허브면({@link AkgSkillSource}) 목록도 재배포 없이 따라 갱신된다.
 *
 * <p>{@code queryId = "{spec.name}#{query.id}"}. v2 는 {@code #{스텝 인덱스}} 였는데,
 * spec v3 에서 {@code queries[]} 는 카탈로그라 순서에 뜻이 없다 — 위치로 가리키면
 * 목록을 재배열하는 것만으로 남의 조회를 가리키게 된다. 주소는 id 다.
 */
public final class QueryPool {

    /**
     * 풀 항목 하나 = 어느 스킬의 어느 조달 수단인가.
     *
     * <p>{@code label} 은 spec 에 없다 — v2 의 {@code steps[].title} 이 사라진 자리를
     * <b>이 조달이 채우는 need 의 {@code what}</b> 이 대신한다. 라벨이 조회의 이름이
     * 아니라 그 조회가 답에 기여하는 것을 말하게 되는데, 그게 v3 가 뒤집은 방향
     * ("답이 조회를 정한다")의 화면상 모습이다.
     */
    public record Query(
            String queryId,
            String skill,
            String id,
            String label,
            String table,
            String sql,
            Map<String, SkillSpec.BindSource> binds,
            List<SkillSpec.SkillInput> inputs) {

        /** 이 스킬의 필수 인자 이름(정렬) — run 이름표(queryKey)의 재료다. */
        public List<String> requiredArgs() {
            return requiredArgsOf(inputs);
        }

        /** 이 조달을 실행하려면 다른 조달 결과가 필요한가. */
        public boolean dependsOnQuery() {
            return binds.values().stream().anyMatch(b -> "query".equals(b.from()));
        }
    }

    private final List<Query> all;
    private final Map<String, Query> byNormalizedId;
    private final Map<String, List<SkillSpec.SkillNeed>> needsBySkill;
    private final Map<String, List<SkillSpec.SkillInput>> inputsBySkill;

    private QueryPool(List<Query> all,
            Map<String, List<SkillSpec.SkillNeed>> needsBySkill,
            Map<String, List<SkillSpec.SkillInput>> inputsBySkill) {
        this.all = all;
        this.needsBySkill = needsBySkill;
        this.inputsBySkill = inputsBySkill;
        Map<String, Query> index = new LinkedHashMap<>();
        for (Query q : all) {
            index.putIfAbsent(normalize(q.queryId()), q);
        }
        this.byNormalizedId = index;
    }

    public static QueryPool of(List<SkillSpec> specs) {
        List<Query> items = new ArrayList<>();
        Map<String, List<SkillSpec.SkillNeed>> needs = new LinkedHashMap<>();
        Map<String, List<SkillSpec.SkillInput>> inputs = new LinkedHashMap<>();

        for (SkillSpec spec : specs != null ? specs : List.<SkillSpec>of()) {
            if (spec == null || spec.queries() == null || spec.name() == null) {
                continue;
            }
            List<SkillSpec.SkillInput> specInputs =
                    spec.inputs() != null ? spec.inputs() : List.of();
            List<SkillSpec.SkillNeed> specNeeds = spec.needs() != null ? spec.needs() : List.of();
            needs.put(spec.name(), specNeeds);
            inputs.put(spec.name(), specInputs);

            for (SkillSpec.SpecQuery query : spec.queries()) {
                if (query == null || query.id() == null
                        || query.sql() == null || query.sql().isBlank()) {
                    continue;
                }
                items.add(new Query(
                        spec.name() + "#" + query.id(),
                        spec.name(),
                        query.id(),
                        labelOf(query.id(), specNeeds),
                        query.table() != null && !query.table().isBlank()
                                ? query.table().trim()
                                : SqlRender.tableOf(query.sql()),
                        query.sql(),
                        query.bindings(),
                        specInputs));
            }
        }
        return new QueryPool(List.copyOf(items), Map.copyOf(needs), Map.copyOf(inputs));
    }

    /**
     * 사람이 읽을 이름 — 이 조달이 채우는 need 들의 {@code what}. 여럿이면 첫 것에
     * 개수를 덧붙인다(카드 라벨이 길어지면 못 읽는다). 아무 need 도 안 쓰는 조달은
     * 죽은 조달이라 id 로 남는다 — foundry 가 생성 시점에 경고할 자리다.
     */
    private static String labelOf(String queryId, List<SkillSpec.SkillNeed> needs) {
        List<String> whats = new ArrayList<>();
        for (SkillSpec.SkillNeed need : needs) {
            if (need == null || need.what() == null) {
                continue;
            }
            for (SkillSpec.Fill fill : need.fills()) {
                if (fill != null && queryId.equals(fill.query()) && !whats.contains(need.what())) {
                    whats.add(need.what());
                }
            }
        }
        if (whats.isEmpty()) {
            return queryId;
        }
        return whats.size() == 1 ? whats.get(0) : whats.get(0) + " 외 " + (whats.size() - 1);
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    public List<Query> all() {
        return all;
    }

    /** enum 에 실을 정식 id 목록. */
    public List<String> ids() {
        return all.stream().map(Query::queryId).toList();
    }

    /**
     * 모델이 준 id 로 조달을 찾는다. 표기 흔들림을 흡수한다 — 스킬 <b>툴</b> 이름은
     * {@code fdc_explain_sensor}, spec 이름은 {@code fdc-explain-sensor} 라 모델이
     * 섞어 쓴다. {@code #} 가 없으면 그 스킬의 조달이 하나뿐일 때만 그것으로 본다:
     * v2 는 "첫 단계"로 폈지만 v3 카탈로그에는 첫째가 없다.
     */
    public Query byId(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            return null;
        }
        String raw = queryId.trim();
        Query hit = byNormalizedId.get(normalize(raw));
        if (hit != null || raw.contains("#")) {
            return hit;
        }
        List<Query> ofSkill = all.stream().filter(q -> normalize(q.skill()).equals(normalize(raw))).toList();
        return ofSkill.size() == 1 ? ofSkill.get(0) : null;
    }

    /** 같은 스킬의 조달들(선언 순 — 표시 순서일 뿐 실행 순서가 아니다). */
    public List<Query> queriesOf(String skill) {
        return all.stream().filter(q -> q.skill().equals(skill)).toList();
    }

    /** 이 스킬이 알아야 하는 것들 — 판정의 입력. 모르는 스킬이면 빈 목록. */
    public List<SkillSpec.SkillNeed> needsOf(String skill) {
        return needsBySkill.getOrDefault(skill, List.of());
    }

    /** 이 스킬의 배선 전량(조달 id → binds) — 도달 가능성 판정의 입력. */
    public Map<String, Map<String, SkillSpec.BindSource>> wiringOf(String skill) {
        Map<String, Map<String, SkillSpec.BindSource>> out = new LinkedHashMap<>();
        for (Query q : queriesOf(skill)) {
            out.put(q.id(), q.binds());
        }
        return out;
    }

    /** 이 스킬의 인자 계약. 모르는 스킬이면 빈 목록. */
    public List<SkillSpec.SkillInput> inputsOf(String skill) {
        return inputsBySkill.getOrDefault(skill, List.of());
    }

    /** 이 스킬의 필수 인자 이름(정렬) — 조달이 하나도 없는 스킬에도 답할 수 있어야 한다. */
    public List<String> requiredArgsOf(String skill) {
        return requiredArgsOf(inputsOf(skill));
    }

    public boolean knows(String skill) {
        return skill != null && needsBySkill.containsKey(skill);
    }

    private static List<String> requiredArgsOf(List<SkillSpec.SkillInput> inputs) {
        return inputs.stream()
                .filter(SkillSpec.SkillInput::required)
                .map(SkillSpec.SkillInput::name)
                .sorted()
                .toList();
    }

    /**
     * 풀 내용 지문 — 같은 스킬 목록이면 같은 값. 스킬 출처가 시변이라(akg 주기
     * refresh) 같은 요청 body 가 다른 판정을 받을 수 있는데, 응답에 이 지문을
     * echo 해 FE 가 그 갈림을 감지한다(#38 T14).
     */
    public String rev() {
        StringBuilder sb = new StringBuilder();
        for (Query q : all) {
            sb.append(q.queryId()).append('|').append(q.sql()).append('\n');
        }
        for (Map.Entry<String, List<SkillSpec.SkillNeed>> e : needsBySkill.entrySet()) {
            for (SkillSpec.SkillNeed need : e.getValue()) {
                sb.append(e.getKey()).append('|').append(need.id()).append('|')
                        .append(need.when()).append('|').append(need.fills()).append('\n');
            }
        }
        return Long.toString(Js.hash(sb.toString()), 36);
    }

    /**
     * 카탈로그 줄에 붙는 의존 표시 — "이건 지금 못 부른다"의 유일한 신호다.
     * v2 는 {@code #0} 이 곧 "먼저 부를 수 있는 것"이었지만 카탈로그에는 첫째가 없다.
     */
    public static final String DEPENDS_MARK = "※ 앞 조달 결과 필요";

    /**
     * 툴 스펙에 실을 목록 설명 — enum 값만으로는 무엇을 조달하는지 알 수 없다.
     * 다른 조달 결과에 매인 항목은 명시한다: 그게 곧 "지금 부를 수 있나"의 답이다.
     */
    public String catalogText() {
        List<String> lines = new ArrayList<>();
        for (Query q : all) {
            StringBuilder line = new StringBuilder("- ").append(q.queryId()).append(" — ")
                    .append(q.label());
            List<String> args = q.requiredArgs();
            if (!args.isEmpty()) {
                line.append(" (인자: ").append(String.join(", ", args)).append(")");
            }
            if (q.dependsOnQuery()) {
                line.append(" ").append(DEPENDS_MARK);
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    private static String normalize(String queryId) {
        return queryId.replace('-', '_').toLowerCase();
    }
}

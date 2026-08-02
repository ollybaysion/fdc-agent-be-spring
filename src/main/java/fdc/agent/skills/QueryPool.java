package fdc.agent.skills;

import fdc.agent.util.Js;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 요청 가능한 조회의 <b>닫힌 목록</b> — 로드된 스킬 spec 들의 {@code steps[]} 를 평면화한 것.
 *
 * <p>풀은 새로 만든 개념이 아니라 <b>이미 있던 것을 꺼낸 것</b>이다. 스킬 실행 경로는
 * 처음부터 {@code spec.steps[].sql} 만 돌렸고 LLM 은 SQL 을 본 적이 없다(메뉴판 방식).
 * 조달 요청만 자유 저작이었다 — 이 클래스는 그 두 갈래를 같은 목록 위에 세운다.
 * 출처가 akg 허브면({@link AkgSkillSource}) 목록도 재배포 없이 따라 갱신된다.
 *
 * <p>{@code queryId = "{spec.name}#{stepIndex}"}. spec 스텝에는 id 필드가 없고
 * {@code title} 은 사람용 라벨이라, 위치가 유일하게 안정된 식별자다.
 */
public final class QueryPool {

    /**
     * 풀 항목 하나 = 어느 스킬의 몇 번째 조회인가. {@code table} 은 원천 테이블명 —
     * spec {@code steps[].table} 이 우선이고, 없으면 SQL FROM 파싱으로 유도한다
     * (둘 다 실패하면 null).
     */
    public record Query(
            String queryId,
            String skill,
            int step,
            int stepCount,
            String title,
            String table,
            String produces,
            String sql,
            Map<String, SkillSpec.BindSource> binds,
            List<SkillSpec.SkillInput> inputs) {

        /** 이 스킬의 필수 인자 이름(정렬) — run 이름표(queryKey)의 재료다. */
        public List<String> requiredArgs() {
            return inputs.stream()
                    .filter(SkillSpec.SkillInput::required)
                    .map(SkillSpec.SkillInput::name)
                    .sorted()
                    .toList();
        }

        /** 이 조회를 실행하려면 앞 단계 결과가 필요한가. */
        public boolean dependsOnStep() {
            return binds.values().stream().anyMatch(b -> "step".equals(b.from()));
        }
    }

    private final List<Query> all;
    private final Map<String, Query> byNormalizedId;

    private QueryPool(List<Query> all) {
        this.all = all;
        Map<String, Query> index = new LinkedHashMap<>();
        for (Query q : all) {
            index.putIfAbsent(normalize(q.queryId()), q);
        }
        this.byNormalizedId = index;
    }

    public static QueryPool of(List<SkillSpec> specs) {
        List<Query> items = new ArrayList<>();
        for (SkillSpec spec : specs != null ? specs : List.<SkillSpec>of()) {
            if (spec == null || spec.steps() == null || spec.name() == null) {
                continue;
            }
            List<SkillSpec.SkillInput> inputs =
                    spec.inputs() != null ? spec.inputs() : List.of();
            for (int i = 0; i < spec.steps().size(); i++) {
                SkillSpec.SkillStep step = spec.steps().get(i);
                if (step == null || step.sql() == null || step.sql().isBlank()) {
                    continue;
                }
                items.add(new Query(
                        spec.name() + "#" + i,
                        spec.name(),
                        i,
                        spec.steps().size(),
                        step.title() != null ? step.title() : (i + 1) + "단계",
                        step.table() != null && !step.table().isBlank()
                                ? step.table().trim()
                                : SqlRender.tableOf(step.sql()),
                        step.produces(),
                        step.sql(),
                        step.binds() != null ? step.binds() : Map.of(),
                        inputs));
            }
        }
        return new QueryPool(List.copyOf(items));
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
     * 모델이 준 id 로 조회를 찾는다. 표기 흔들림을 흡수한다 — 스킬 <b>툴</b> 이름은
     * {@code fdc_explain_sensor}, spec 이름은 {@code fdc-explain-sensor} 라 모델이
     * 섞어 쓴다. {@code #n} 이 없으면 첫 단계로 본다(절차의 시작을 부르는 흔한 축약).
     */
    public Query byId(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            return null;
        }
        String raw = queryId.trim();
        Query hit = byNormalizedId.get(normalize(raw));
        return hit != null || raw.contains("#") ? hit : byNormalizedId.get(normalize(raw + "#0"));
    }

    /** 같은 스킬의 조회들(단계 순). */
    public List<Query> stepsOf(String skill) {
        return all.stream().filter(q -> q.skill().equals(skill)).toList();
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
        return Long.toString(Js.hash(sb.toString()), 36);
    }

    /**
     * 툴 스펙에 실을 목록 설명 — enum 값만으로는 무엇을 조회하는지 알 수 없다.
     * 앞 단계 의존은 명시한다: 그게 곧 "지금 부를 수 있나"의 답이다.
     */
    public String catalogText() {
        List<String> lines = new ArrayList<>();
        for (Query q : all) {
            StringBuilder line = new StringBuilder("- ").append(q.queryId()).append(" — ")
                    .append(q.title());
            List<String> args = q.requiredArgs();
            if (!args.isEmpty()) {
                line.append(" (인자: ").append(String.join(", ", args)).append(")");
            }
            if (q.dependsOnStep()) {
                line.append(" ※ 앞 단계 결과 필요");
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    private static String normalize(String queryId) {
        return queryId.replace('-', '_').toLowerCase();
    }
}

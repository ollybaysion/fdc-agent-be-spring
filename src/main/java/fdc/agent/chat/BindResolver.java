package fdc.agent.chat;

import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 조회 스텝의 SQL 에 박을 값들을 해석한다 — {@code from:"arg"} 는 인자에서,
 * {@code from:"step"} 은 <b>도착한 앞 단계 스냅샷에서</b>. 값 추출·pick 검증의
 * 결정론 코어이고, 채팅 경로({@link DataRequestTool})와 패널 판정 경로
 * ({@link PanelJudge})가 이 한 곳을 공유한다(#38 T13) — 두 경로의 진행 판정이
 * 서로 다른 해석으로 갈라지면 안 된다.
 *
 * <p>바인드는 선언 순서로 해석하고 <b>첫 문제에서 멈춘다</b> — 여러 사유를
 * 모아 봐야 첫 사유를 풀기 전엔 나머지가 유효한지 알 수 없다.
 */
public final class BindResolver {
    private BindResolver() {
    }

    /** 여러 값 중 고르라고 보여 줄 후보 수 상한(표시용 — 후보 자체는 전량 유지). */
    private static final int MAX_CANDIDATES = 10;

    /**
     * @param pick 갈림길 선택({@code column: 값}) — 없으면 빈 맵으로 취급
     * @param arrived queryKey → 도착한 스냅샷(행 있음 또는 0행 확인). 없으면 null
     */
    public static BindOutcome resolve(
            QueryPool.Query query, Map<String, String> runArgs,
            Map<String, String> pick, Function<String, ChatDataSnapshot> arrived) {
        Map<String, String> givenPick = pick != null ? pick : Map.of();
        Map<String, String> binds = new LinkedHashMap<>();
        for (Map.Entry<String, SkillSpec.BindSource> e : query.binds().entrySet()) {
            SkillSpec.BindSource src = e.getValue();
            if ("arg".equals(src.from())) {
                String value = runArgs.get(src.arg());
                if (value == null || value.isBlank()) {
                    return new BindOutcome.Blocked("인자 " + src.arg() + " 값이 필요합니다.");
                }
                binds.put(e.getKey(), value);
                continue;
            }
            BindOutcome step = fromEarlierStep(e.getKey(), query, runArgs, givenPick, src, arrived);
            if (!(step instanceof BindOutcome.Ready ready)) {
                return step;
            }
            binds.putAll(ready.binds());
        }
        return new BindOutcome.Ready(binds);
    }

    /** 앞 단계 스냅샷에서 한 값을 고른다 — 1행이면 그대로, 여러 행이면 pick, 없으면 중단. */
    private static BindOutcome fromEarlierStep(
            String bindKey, QueryPool.Query query, Map<String, String> runArgs,
            Map<String, String> pick, SkillSpec.BindSource src,
            Function<String, ChatDataSnapshot> arrived) {
        if (src.step() == null) {
            // 로드 시 검증(SkillLoader.validateBinds)이 걸렀어야 할 spec — 조용히 이상한
            // 문장을 만드느니 여기서 멈춘다.
            return new BindOutcome.Blocked("이 조회의 배선이 온전하지 않아 요청할 수 없습니다.");
        }
        int step = src.step();
        String sourceKey = QueryKey.of(query.skill(), step, runArgs, query.requiredArgs());
        ChatDataSnapshot source = arrived.apply(sourceKey);
        if (source == null) {
            return new BindOutcome.MissingUpstream(step, query.skill() + "#" + step);
        }
        if (source.isEmptyResult()) {
            return new BindOutcome.EmptyUpstream(step);
        }

        List<String> values = QueryProgress.valuesOf(source, src.column());
        if (values.isEmpty()) {
            return new BindOutcome.Blocked((step + 1) + "단계 결과에서 " + src.column()
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
                    // 표에 있는 값만 통과 — 고르되 지어내지는 못한다.
                    return new BindOutcome.Ready(Map.of(bindKey, v));
                }
            }
            return new BindOutcome.Blocked("pick 한 " + src.column() + "=" + picked
                    + " 은 " + (step + 1) + "단계 결과에 없는 값입니다. " + candidates(values));
        }
        if (values.size() > 1) {
            return new BindOutcome.NeedPick(step, src.column(), values);
        }
        return new BindOutcome.Ready(Map.of(bindKey, values.get(0)));
    }

    /** 후보 나열 프로즈 — 채팅 되먹임과 pick 거절 사유가 같은 표기를 쓴다. */
    public static String candidates(List<String> values) {
        List<String> shown = values.size() > MAX_CANDIDATES ? values.subList(0, MAX_CANDIDATES) : values;
        return "가능한 값: " + String.join(", ", shown)
                + (values.size() > shown.size() ? " 외 " + (values.size() - shown.size()) + "개" : "");
    }
}

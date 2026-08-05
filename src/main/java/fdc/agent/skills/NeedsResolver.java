package fdc.agent.skills;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 필요 데이터(명세)와 도착 데이터(행)를 잇는 <b>결정론 판정</b> — 세 번째·네 번째 칸이
 * 실행 시점에 만나는 자리다. v2 에는 이 자리가 없었다: 판정기가 볼 수 있는 것이 행
 * 수뿐이라 "조회가 다 왔나"로 종결을 읽었고, "알아야 할 걸 다 알았나"는 LLM 재량에
 * 떠 있었다.
 *
 * <p>판정은 셋으로 떨어진다 — <b>충분 · 조달가능 · 답불가</b>. 넷째 갈래(지식 없음)는
 * 스킬이 없는 경우라 이 층 밖이다(라우팅). 종결은 "조달할 것이 없으면"이지 "전부
 * 도착하면"이 아니다: 못 채운 need 가 조달로 안 풀리면 그것도 끝난 것이고, 그 사실이
 * 답이다.
 *
 * <p><b>미도착과 0행은 다른 사실이다.</b> {@link Rows} 의 계약이 그 둘을 가른다 —
 * {@code null} 은 아직 안 온 것(조달 가능), 빈 목록은 왔는데 그 컬럼에 값이 없는 것
 * (더 기다려도 안 온다). 이 구분이 무너지면 판정이 "없음"을 "아직"으로 읽어 영영
 * 끝나지 않거나, 그 반대로 도착 전에 답불가를 선언한다.
 *
 * <p>무상태 순수함수다 — 같은 (needs, 도착)은 같은 판정이다.
 */
public final class NeedsResolver {
    private NeedsResolver() {
    }

    /**
     * 도착한 조달 결과를 컬럼 단위로 읽는 창구.
     *
     * @return 미도착이면 {@code null}, 도착했으면 {@link Cell}.
     */
    @FunctionalInterface
    public interface Rows {
        Cell valuesOf(String queryId, String column);
    }

    /**
     * 도착한 조달의 한 컬럼. 상태가 셋이라 {@code List<String>} 으로는 못 적는다 —
     * 값이 없다 / 값이 있고 하나로 정해진다 / <b>값은 있는데 무엇인지 못 읽었다</b>.
     *
     * <p>셋째가 {@link #OPAQUE} 다. {@code /chat/data} 의 경량 판정은 요약
     * ({@code snapshotIndex}: 컬럼명·행수)만 받고 셀을 안 받는데, 그때도 "그 컬럼이
     * 결과에 있고 행이 왔다"까지는 사실이다. 찼다고는 하되 게이트는 못 연다 —
     * 값을 모르면서 갈래를 고르면 지어내는 것이다.
     */
    public record Cell(boolean filled, String value) {

        /** 도착했는데 그 컬럼에 값이 없다(0행이거나 전부 NULL). */
        public static final Cell EMPTY = new Cell(false, null);

        /** 값은 있는데 실물을 못 읽었다(경량 판정). */
        public static final Cell OPAQUE = new Cell(true, null);

        /** 갈림길(값 여럿)은 고르지 않는다 — 찼다고는 하되 스칼라는 비운다. */
        public static Cell of(List<String> values) {
            if (values == null || values.isEmpty()) {
                return EMPTY;
            }
            return values.size() == 1 ? new Cell(true, values.get(0)) : OPAQUE;
        }
    }

    /** need 하나의 상태. */
    public enum State {
        /** {@code when} 이 안 맞아 이번 질문에서는 알 필요가 없다. */
        INACTIVE,
        /** {@code when} 이 걸린 선행 need 가 아직 정해지지 않았다 — 활성 여부 자체를 모른다. */
        PENDING_GATE,
        /** 활성인데 아직 안 찼고, 지목한 조달 중 미도착이 남았다. */
        UNFILLED,
        /** 활성이고 찼다. */
        FILLED,
        /** 활성인데 채울 방법이 없다 — {@code filledBy} 가 비었거나 지목한 조달이 전부 빈손으로 도착. */
        UNPROCURABLE
    }

    /** 질문의 상태 — 4갈래에서 B(스킬 없음)를 뺀 셋. */
    public enum Outcome {
        /** 활성 need 가 전부 찼다. */
        SUFFICIENT,
        /** 아직 조달할 것이 남았다. */
        PROCURABLE,
        /** 못 찬 need 가 있는데 조달로는 안 풀린다. */
        UNANSWERABLE
    }

    /**
     * @param value 게이트 비교에 쓸 스칼라 값. 값이 여럿이거나(갈림길) 실물을 못 읽었으면
     *     {@code null} — 상태는 그래도 {@link State#FILLED} 다(데이터는 있다).
     */
    public record NeedStatus(String id, String what, State state, String value) {

        public boolean active() {
            return state != State.INACTIVE;
        }
    }

    /**
     * @param wanted 지금 돌려야 할 조달 수단의 id — 활성·미충족 need 가 지목했고
     *     아직 안 온 것들(선언 순서). 이것이 "쿼리 실행 조건"의 실물이다: 카탈로그에
     *     있다고 도는 게 아니라 <b>필요한 need 가 부를 때</b> 돈다.
     */
    public record Resolution(List<NeedStatus> needs, List<String> wanted, Outcome outcome) {

        /** 절차가 끝났는가 — 충분이든 답불가든 더 조달할 것이 없으면 끝이다. */
        public boolean terminal() {
            return outcome != Outcome.PROCURABLE;
        }

        public List<NeedStatus> in(State... states) {
            List<State> want = List.of(states);
            return needs.stream().filter(n -> want.contains(n.state())).toList();
        }

        /** 활성인데 못 찬 것들 — 그대로 "이건 못 알아냈다"의 목록이고, 조달 로드맵이다. */
        public List<NeedStatus> unmet() {
            return in(State.UNFILLED, State.UNPROCURABLE, State.PENDING_GATE);
        }
    }

    /** {@code sensor_kind = PHYSICAL} 꼴. 값의 따옴표는 벗긴다. */
    private static final Pattern WHEN =
            Pattern.compile("\\s*([a-z][a-z0-9_]*)\\s*(!=|=)\\s*(.+?)\\s*");

    /** {@code when} 한 줄의 문법 — 로드 검증과 판정이 같은 것을 읽는다. */
    public record Gate(String needId, boolean negated, String expected) {

        /** 문법에 안 맞으면 null. */
        public static Gate parse(String when) {
            if (when == null || when.isBlank()) {
                return null;
            }
            Matcher m = WHEN.matcher(when);
            if (!m.matches()) {
                return null;
            }
            return new Gate(m.group(1), "!=".equals(m.group(2)), unquote(m.group(3)));
        }

        private static String unquote(String value) {
            String v = value.trim();
            if (v.length() >= 2 && (v.startsWith("'") && v.endsWith("'")
                    || v.startsWith("\"") && v.endsWith("\""))) {
                return v.substring(1, v.length() - 1);
            }
            return v;
        }
    }

    /** 게이트 판정 — 참/거짓/아직 모름. */
    private enum GateState {
        OPEN, SHUT, UNKNOWN
    }

    /**
     * 이 조달이 <b>앞으로라도</b> 돌 수 있는가. 바인드 출처가 이미 빈손으로 확정됐으면
     * 영영 못 돈다.
     *
     * <p>이게 없으면 답불가가 영영 안 나온다: 센서가 등록돼 있지 않아 첫 조달이 0행이면
     * 그 값으로 이어가는 조달은 못 도는데, need 쪽에서는 그저 "미도착"으로 보여서
     * 판정이 계속 조달가능에 머문다 — 절차가 끝나지 않고 종결 서술도 안 나간다.
     */
    @FunctionalInterface
    public interface Reach {
        boolean canRun(String queryId);
    }

    /** 배선을 모르는 호출자용 — 전부 돌 수 있다고 본다. */
    public static final Reach ANY = queryId -> true;

    /**
     * 바인드를 거슬러 올라가며 도달 가능성을 판정하는 {@link Reach}. 판정 규칙이 한
     * 곳에 있어야 채팅 경로·패널 판정·스킬 실행이 같은 결론을 낸다.
     *
     * @param wiring 조달 id → 그 조달의 바인드 배선
     */
    public static Reach reachOf(Map<String, Map<String, SkillSpec.BindSource>> wiring, Rows rows) {
        Map<String, Map<String, SkillSpec.BindSource>> byId =
                wiring != null ? wiring : Map.of();
        return queryId -> canRun(queryId, byId, rows, new LinkedHashSet<>());
    }

    private static boolean canRun(String queryId,
            Map<String, Map<String, SkillSpec.BindSource>> byId, Rows rows, Set<String> onPath) {
        Map<String, SkillSpec.BindSource> binds = byId.get(queryId);
        if (binds == null || !onPath.add(queryId)) {
            // 모르는 조달, 또는 순환(로드 검증이 거절했어야 할 spec) — 보수적으로 못 돈다.
            return false;
        }
        for (SkillSpec.BindSource src : binds.values()) {
            if (src == null || !"query".equals(src.from())) {
                continue;
            }
            Cell cell = rows.valuesOf(src.query(), src.column());
            if (cell == null) {
                if (!canRun(src.query(), byId, rows, onPath)) {
                    return false;
                }
            } else if (!cell.filled()) {
                return false; // 도착했는데 이어갈 값이 없다.
            }
        }
        onPath.remove(queryId);
        return true;
    }

    public static Resolution resolve(List<SkillSpec.SkillNeed> needs, Rows rows) {
        return resolve(needs, rows, ANY);
    }

    public static Resolution resolve(
            List<SkillSpec.SkillNeed> needs, Rows rows, Reach reach) {
        return resolve(needs, rows, reach, Map.of());
    }

    /**
     * @param given 조달 배선 밖에서 채워진 need — {@code id → 값}. 채움 폭포의 3차가
     *     여기로 들어온다: 요청한 조회가 아니라 <b>다른 형태로 받은 데이터</b>에서
     *     그 사실을 읽어 낸 경우다. 지목한 조달이 없는 need({@code filledBy} 가 빈
     *     것)도 이 자리로는 채워진다 — 조달 수단이 없다는 것과 알 수 없다는 것은
     *     다른 말이고, 사람이 이미 알고 있으면 그 사실이 이긴다.
     *     <p>{@code when} 은 그대로 판정한다. 갈래 밖인 need 는 값을 받아도 비활성이다.
     */
    public static Resolution resolve(
            List<SkillSpec.SkillNeed> needs, Rows rows, Reach reach, Map<String, String> given) {
        List<SkillSpec.SkillNeed> all = needs != null ? needs : List.of();
        Map<String, String> external = given != null ? given : Map.<String, String>of();
        Map<String, NeedStatus> status = new LinkedHashMap<>();

        // 게이트가 앞 need 의 값을 보므로 한 번에 다 정해지지 않는다. 선언 순서에
        // 기대지 않고 안 변할 때까지 다시 본다 — 순환은 로드 검증이 이미 거절했다.
        for (int pass = 0; pass <= all.size(); pass++) {
            boolean changed = false;
            for (SkillSpec.SkillNeed need : all) {
                if (need == null || need.id() == null) {
                    continue;
                }
                NeedStatus next = statusOf(need, status, rows, reach, external);
                NeedStatus prev = status.put(need.id(), next);
                changed |= !next.equals(prev);
            }
            if (!changed) {
                break;
            }
        }

        List<NeedStatus> ordered = new ArrayList<>();
        for (SkillSpec.SkillNeed need : all) {
            NeedStatus hit = need != null ? status.get(need.id()) : null;
            if (hit != null) {
                ordered.add(hit);
            }
        }
        return new Resolution(
                List.copyOf(ordered), wanted(all, status, rows, reach), outcome(ordered));
    }

    private static NeedStatus statusOf(SkillSpec.SkillNeed need,
            Map<String, NeedStatus> status, Rows rows, Reach reach, Map<String, String> given) {
        GateState gate = gateOf(need.when(), status);
        if (gate == GateState.SHUT) {
            return new NeedStatus(need.id(), need.what(), State.INACTIVE, null);
        }
        if (gate == GateState.UNKNOWN) {
            return new NeedStatus(need.id(), need.what(), State.PENDING_GATE, null);
        }
        String external = given.get(need.id());
        if (external != null && !external.isBlank()) {
            return new NeedStatus(need.id(), need.what(), State.FILLED, external.trim());
        }

        boolean awaited = false;
        for (SkillSpec.Fill fill : need.fills()) {
            Cell cell = fill == null ? null : rows.valuesOf(fill.query(), fill.column());
            if (cell == null) {
                // 미도착 — 앞으로라도 돌 수 있을 때만 기다림으로 친다. 다음 대안도
                // 보고 나서 판단한다(filledBy 는 OR).
                awaited |= fill != null && reach.canRun(fill.query());
                continue;
            }
            if (!cell.filled()) {
                continue; // 도착했는데 그 컬럼엔 값이 없다.
            }
            return new NeedStatus(need.id(), need.what(), State.FILLED, cell.value());
        }
        return new NeedStatus(need.id(), need.what(),
                awaited ? State.UNFILLED : State.UNPROCURABLE, null);
    }

    /**
     * 선행 need 의 값으로 이 need 가 열리는가.
     *
     * <p>선행이 <b>비활성</b>이면 그 갈래 자체가 안 열린 것이므로 이쪽도 닫는다.
     * 선행이 <b>조달 불가</b>면 영영 모르는 것이라 열지도 닫지도 않는다 —
     * {@link State#PENDING_GATE} 로 남아 판정을 답불가로 끌고 간다. 모르는 것을
     * "아니다"로 접으면 못 답한 질문이 충분으로 둔갑한다.
     */
    private static GateState gateOf(String when, Map<String, NeedStatus> status) {
        if (when == null || when.isBlank()) {
            return GateState.OPEN;
        }
        Gate gate = Gate.parse(when);
        if (gate == null) {
            return GateState.UNKNOWN; // 로드 검증이 걸렀어야 할 spec — 답하지 않는 쪽으로 닫는다.
        }
        NeedStatus ref = status.get(gate.needId());
        if (ref == null) {
            return GateState.UNKNOWN;
        }
        return switch (ref.state()) {
            case INACTIVE -> GateState.SHUT;
            case FILLED -> ref.value() == null
                    ? GateState.UNKNOWN
                    : gate.expected().equalsIgnoreCase(ref.value().trim()) != gate.negated()
                            ? GateState.OPEN
                            : GateState.SHUT;
            default -> GateState.UNKNOWN;
        };
    }

    /** 활성·미충족 need 가 지목했고 아직 안 왔으며 돌 수 있는 조달들(중복 접음, 선언 순서). */
    private static List<String> wanted(List<SkillSpec.SkillNeed> needs,
            Map<String, NeedStatus> status, Rows rows, Reach reach) {
        Set<String> out = new LinkedHashSet<>();
        for (SkillSpec.SkillNeed need : needs) {
            NeedStatus hit = need != null ? status.get(need.id()) : null;
            if (hit == null || hit.state() != State.UNFILLED) {
                continue;
            }
            for (SkillSpec.Fill fill : need.fills()) {
                if (fill != null && rows.valuesOf(fill.query(), fill.column()) == null
                        && reach.canRun(fill.query())) {
                    out.add(fill.query());
                }
            }
        }
        return List.copyOf(out);
    }

    private static Outcome outcome(List<NeedStatus> needs) {
        boolean procurable = false;
        boolean stuck = false;
        for (NeedStatus n : needs) {
            switch (n.state()) {
                case UNFILLED -> procurable = true;
                case UNPROCURABLE, PENDING_GATE -> stuck = true;
                default -> {
                }
            }
        }
        if (procurable) {
            return Outcome.PROCURABLE;
        }
        return stuck ? Outcome.UNANSWERABLE : Outcome.SUFFICIENT;
    }
}

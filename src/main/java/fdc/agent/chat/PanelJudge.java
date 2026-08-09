package fdc.agent.chat;

import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.DataRequest;
import fdc.agent.contract.PanelEvent;
import fdc.agent.contract.QueryScope;
import fdc.agent.contract.RequestState;
import fdc.agent.contract.RunDecl;
import fdc.agent.contract.RunProgress;
import fdc.agent.contract.SnapshotIndexEntry;
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
 * panel-judge — 데이터 패널 상태의 <b>결정론 판정</b> (#38, {@code POST /chat/data} 의
 * 심장). 패널의 모든 수정·입력이 이 판정을 부르고, BE 는 절차의 진행·종결을 인지하고
 * <b>조달 원장</b>을 만든다 — 이 절차가 무엇을 조회해야 하고 각각이 지금 어떤 상태인지의
 * 전량. FE 는 그것을 replace 해서 그리기만 한다: 스킬을 읽고 카드를 배치하는 판단은
 * 화면 쪽에 있으면 안 된다.
 *
 * <p>판정의 기준이 spec v3 에서 바뀌었다: <b>"조회가 다 왔나"에서 "알아야 할 걸 다
 * 알았나"로</b>. 전자는 도착한 행 수로 답할 수 있어서 v2 가 그렇게 했지만, 그건
 * 조회의 상태이지 질문의 상태가 아니다. 실제 판정은 {@link NeedsResolver} 가 하고
 * 여기서는 도착 데이터를 그 판정기가 읽을 수 있게 대어 준다.
 *
 * <p><b>무상태 순수함수다.</b> 판정 입력 = 도착 스냅샷 요약({@code snapshotIndex},
 * 체크된 것만 — T4) ∪ 명시된 절차 선언({@code runs[]} — T3). 서버는 아무것도
 * 기억하지 않으므로 같은 body 는 같은 판정이고(스킬 풀이 갈리면 {@code poolRev} 로
 * 드러난다 — T14), 멱등·재시도는 eventId/revision echo 로 FE 가 매듭짓는다(T7).
 *
 * <p>LLM 이 남는 자리는 <b>종결 서술뿐</b>이다. 서술 여부도 여기서 결정론으로
 * 판정하고({@link Verdict#narration}), 문장 생성만 호출자가 LLM 에 맡긴다.
 */
public final class PanelJudge {
    private PanelJudge() {
    }

    /**
     * {@code POST /api/fdc/v1/chat/data} 요청 body (demo-fe #163 짝 계약,
     * 느슨하게 수용 — 빠진 필드는 null, 모르는 필드는 무시).
     *
     * <p>{@code pasted} 는 붙여넣기 원문이다(#64 메시지 MVP) — 실려 오면 이
     * 왕복은 패널 판정이 아니라 <b>메시지 판정 전용</b>이고, 응답은
     * {@code formattedMessage} 하나로 답한다. {@code pastedForce} 는 사용자가
     * "이건 메시지다"라고 명시한 경우다 — 스니프를 건너뛰고 무조건 포맷팅한다.
     */
    public record PanelBody(
            String eventId,
            Integer revision,
            PanelEvent event,
            List<HistoryMessage> messages,
            List<SnapshotIndexEntry> snapshotIndex,
            List<ChatDataSnapshot> snapshots,
            List<RunDecl> runs,
            Map<String, Map<String, String>> inputs,
            QueryScope scope,
            String pasted,
            Boolean pastedForce) {
    }

    /**
     * 종결 서술 지시 — run 정체와 도착 실물 전부, 그리고 <b>무엇을 알아냈고 무엇이
     * 못 채워진 채 끝났는가</b>({@code resolution}). 판정기는 여기까지만 내놓고
     * 문장(맥락 섹션) 합성은 {@link NarrationPrompt} 소관이다.
     */
    public record Narration(
            String runLabel,
            String skill,
            Map<String, String> args,
            List<QueryArrival> arrivals,
            NeedsResolver.Resolution resolution) {
    }

    /**
     * 도착한 조달 하나 — 판정 집합의 요약({@code hit})과 행 실물({@code full}).
     * {@code full} 은 0행이거나 이 요청에 rows 가 안 실렸으면 null 일 수 있다.
     */
    public record QueryArrival(
            QueryPool.Query query, SnapshotIndexEntry hit, ChatDataSnapshot full) {
    }

    /**
     * 판정 결과 — 응답 페이로드의 재료 전부.
     *
     * @param ledger 조달 원장 — 판정에 든 절차들의 조달 <b>전량</b>이 상태를 달고 실린다.
     *     새로 생긴 것만 흘리는 이벤트가 아니라 매번 전부인 상태이므로, FE 는 replace
     *     하면 되고 멱등성은 규칙이 아니라 구조다.
     */
    public record Verdict(
            List<RunProgress> runsProgress,
            List<String> terminalRuns,
            List<DataRequest> ledger,
            Narration narration) {
    }

    public static Verdict judge(QueryPool pool, PanelBody body) {
        return judge(pool, body, Map.of());
    }

    /**
     * @param altFills 결정론 밖에서 채워진 사실 — {@link AltFillJudge} 가 다른 표에서
     *     읽어 낸 것들, run 키({@link #runKey})로 묶여 있다. 비어 있으면 순수 결정론
     *     판정이고, 실려 있어도 {@code when} 갈래와 종결 규칙은 그대로다.
     */
    public static Verdict judge(
            QueryPool pool, PanelBody body, Map<String, List<AltFillJudge.AltFill>> altFills) {
        Map<String, SnapshotIndexEntry> index = effectiveIndex(body);
        Map<String, ChatDataSnapshot> rows = rowsByKey(body, index);

        List<RunSlot> slots = assembleRuns(pool, body, index);

        List<RunProgress> runsProgress = new ArrayList<>();
        List<String> terminalRuns = new ArrayList<>();
        List<DataRequest> ledger = new ArrayList<>();
        for (RunSlot slot : slots) {
            List<AltFillJudge.AltFill> alt = altFills.getOrDefault(
                    runKey(slot.skill(), slot.args(), pool), List.of());
            RunProgress progress = judgeRun(pool, slot, index, rows, alt, ledger);
            runsProgress.add(progress);
            if (progress.terminal()) {
                terminalRuns.add(progress.label());
            }
        }

        Narration narration = narrationOf(pool, body, index, rows, slots, altFills);
        return new Verdict(List.copyOf(runsProgress), List.copyOf(terminalRuns),
                List.copyOf(ledger), narration);
    }

    /** 절차 하나를 가리키는 안정 키 — 스킬과 run 이름표. 판정 밖에서 run 을 지목할 때 쓴다. */
    public static String runKey(String skill, Map<String, String> args, QueryPool pool) {
        return skill + " " + QueryKey.argsPart(args, pool.requiredArgsOf(skill));
    }

    // ── 판정 집합 ────────────────────────────────────────────────────────────

    /**
     * 판정 집합 = 체크된(included) 스냅샷 요약, 같은 키는 capturedAt 최신 1건(T9).
     * {@code snapshotIndex} 가 없으면 {@code snapshots} 에서 유도한다(관용) —
     * 경량 모드를 아직 안 쓰는 클라이언트도 판정은 받는다.
     */
    private static Map<String, SnapshotIndexEntry> effectiveIndex(PanelBody body) {
        List<SnapshotIndexEntry> entries = body.snapshotIndex() != null
                ? body.snapshotIndex()
                : derivedIndex(body.snapshots());
        Map<String, SnapshotIndexEntry> byKey = new LinkedHashMap<>();
        for (SnapshotIndexEntry e : entries) {
            if (e == null || e.queryKey() == null || e.queryKey().isBlank() || !e.isIncluded()) {
                continue;
            }
            String key = e.queryKey().trim();
            SnapshotIndexEntry prev = byKey.get(key);
            if (prev == null || isNewer(prev.capturedAt(), e.capturedAt())) {
                byKey.put(key, e);
            }
        }
        return byKey;
    }

    private static List<SnapshotIndexEntry> derivedIndex(List<ChatDataSnapshot> snapshots) {
        List<SnapshotIndexEntry> out = new ArrayList<>();
        for (ChatDataSnapshot s : snapshots != null ? snapshots : List.<ChatDataSnapshot>of()) {
            if (s == null) {
                continue;
            }
            Integer rowCount = s.hasRows() ? s.rows().size()
                    : s.isEmptyResult() ? 0
                    : null;
            out.add(new SnapshotIndexEntry(
                    s.queryKey(), s.label(), s.capturedAt(), s.columns(), rowCount, null, true));
        }
        return out;
    }

    /** rows 실물 — 판정 집합(index)에 있는 키만 받는다(T4). 같은 키는 최신 1건(T9). */
    private static Map<String, ChatDataSnapshot> rowsByKey(
            PanelBody body, Map<String, SnapshotIndexEntry> index) {
        Map<String, ChatDataSnapshot> byKey = new LinkedHashMap<>();
        for (ChatDataSnapshot s : body.snapshots() != null ? body.snapshots() : List.<ChatDataSnapshot>of()) {
            if (s == null || s.queryKey() == null || !s.arrived()) {
                continue;
            }
            String key = s.queryKey().trim();
            if (!index.containsKey(key)) {
                continue;
            }
            ChatDataSnapshot prev = byKey.get(key);
            if (prev == null || isNewer(prev.capturedAt(), s.capturedAt())) {
                byKey.put(key, s);
            }
        }
        return byKey;
    }

    /** {@code candidate} 가 더 이르면 false — ISO 문자열 사전순 비교, null 은 가장 이르다. */
    private static boolean isNewer(String current, String candidate) {
        if (candidate == null) {
            return false;
        }
        return current == null || current.compareTo(candidate) <= 0;
    }

    // ── run 조립 ─────────────────────────────────────────────────────────────

    /**
     * 절차 하나 — 선언({@code runs[]})의 args 는 원문, 도착 키에서만 유도된 run 의
     * args 는 키 파싱 복원이다.
     *
     * <p>{@code declared} 가 그 둘을 가른다. 키는 <b>손실 인코딩</b>이라({@code # & =}
     * 를 {@code _} 로 접는다) 복원한 args 로 SQL 을 만들면 사람이 사내에서 실행할
     * 문장에 원문과 다른 값이 박힐 수 있다. 미선언 run 도 진행은 보고하되 원장 줄은
     * {@link RequestState#READY} 로 올라가지 않는다.
     */
    private record RunSlot(
            String skill, String argsPart, Map<String, String> args, boolean declared) {
    }

    private static List<RunSlot> assembleRuns(
            QueryPool pool, PanelBody body, Map<String, SnapshotIndexEntry> index) {
        Map<String, RunSlot> slots = new LinkedHashMap<>();

        for (RunDecl decl : body.runs() != null ? body.runs() : List.<RunDecl>of()) {
            if (decl == null || decl.skill() == null || decl.skill().isBlank()) {
                continue;
            }
            String skill = decl.skill().trim();
            Map<String, String> args = decl.args() != null ? decl.args() : Map.of();
            if (!pool.knows(skill)) {
                // 등재되지 않은 스킬 — 무음으로 버리지 않고 보고 대상으로 남긴다.
                slots.putIfAbsent("?" + skill,
                        new RunSlot(skill, QueryKey.argsPart(args, List.copyOf(args.keySet())),
                                args, true));
                continue;
            }
            String argsPart = QueryKey.argsPart(args, pool.requiredArgsOf(skill));
            slots.putIfAbsent(skill + " " + argsPart, new RunSlot(skill, argsPart, args, true));
        }

        for (String key : index.keySet()) {
            QueryKey.Parsed parsed = QueryKey.parse(key);
            QueryPool.Query query = parsed == null ? null : pool.byId(parsed.queryId());
            if (query == null) {
                continue; // 풀 밖 키(자유 저작 스냅샷) — 진행으로 읽지 않는다.
            }
            slots.putIfAbsent(query.skill() + " " + parsed.argsPart(),
                    new RunSlot(query.skill(), parsed.argsPart(),
                            QueryKey.parseArgs(parsed.argsPart()), false));
        }
        return List.copyOf(slots.values());
    }

    // ── run 하나의 판정 ──────────────────────────────────────────────────────

    private static RunProgress judgeRun(
            QueryPool pool, RunSlot slot,
            Map<String, SnapshotIndexEntry> index, Map<String, ChatDataSnapshot> rows,
            List<AltFillJudge.AltFill> altFills, List<DataRequest> ledger) {
        String label = label(slot);
        if (!pool.knows(slot.skill())) {
            return new RunProgress(slot.skill(), slot.args(), label, 0, 0, List.of(), false,
                    "UNKNOWN_SKILL", List.of(),
                    List.of(new RunProgress.StepHold(slot.skill(), "등재되지 않은 스킬입니다.")));
        }

        ArrivalLens lens = lensOf(slot, index, rows, null);
        NeedsResolver.Resolution resolution = resolve(pool, slot, lens, altFills);

        List<RunProgress.Need> needs =
                needsOf(resolution, pool.needsOf(slot.skill()), lens, altFills);
        int active = (int) resolution.needs().stream()
                .filter(NeedsResolver.NeedStatus::active).count();
        int met = resolution.in(NeedsResolver.State.FILLED).size();
        List<String> wanted = resolution.wanted().stream()
                .map(id -> slot.skill() + "#" + id)
                .toList();

        ledger.addAll(ledgerOf(pool, slot, index, rows, lens, resolution));

        return new RunProgress(slot.skill(), slot.args(), label, active, met, wanted,
                resolution.terminal(), resolution.outcome().name(), needs, null);
    }

    // ── 조달 원장 ────────────────────────────────────────────────────────────

    /**
     * 이 절차의 조달 <b>전량</b>을 상태와 함께 원장 줄로. 도착한 것도, 지금 열 수
     * 있는 것도, 앞 조달을 기다리는 것도, 영영 못 도는 것도 모두 실린다 — 무엇을
     * 화면에 보일지는 FE 가 판단하지 않고 이 상태를 그대로 그린다.
     *
     * <p>상태의 근거는 {@link NeedsResolver.Resolution} 하나다. {@code wanted} 는
     * "활성·미충족 need 가 지목했고 아직 안 왔으며 앞으로라도 돌 수 있는 조달"이므로
     * 그 목록이 곧 <b>열어야 할 것</b>이고, 그 안에서 바인드가 실제로 풀리는지만
     * {@link BindResolver} 에 묻는다. 목록 밖은 도착했거나, 이 질문에서 안 부르거나,
     * 영영 못 도는 것이다.
     */
    private static List<DataRequest> ledgerOf(
            QueryPool pool, RunSlot slot,
            Map<String, SnapshotIndexEntry> index, Map<String, ChatDataSnapshot> rows,
            ArrivalLens lens, NeedsResolver.Resolution resolution) {
        NeedsResolver.Reach reach = NeedsResolver.reachOf(pool.wiringOf(slot.skill()), lens);
        Set<String> wanted = new LinkedHashSet<>(resolution.wanted());
        Map<String, List<String>> needsByQuery = needsByQuery(pool.needsOf(slot.skill()));
        Set<String> pendingGate = new LinkedHashSet<>();
        for (NeedsResolver.NeedStatus n : resolution.in(NeedsResolver.State.PENDING_GATE)) {
            pendingGate.add(n.id());
        }

        List<DataRequest> out = new ArrayList<>();
        for (QueryPool.Query query : pool.queriesOf(slot.skill())) {
            String key = keyOf(slot, query.queryId());
            List<String> served = needsByQuery.getOrDefault(query.id(), List.of());
            SnapshotIndexEntry hit = index.get(key);
            if (hit != null && hit.arrived()) {
                out.add(entry(key, query, slot, RequestState.ARRIVED, null, null, served));
                continue;
            }
            if (wanted.contains(query.id())) {
                out.add(open(key, query, slot, rows, served));
                continue;
            }
            if (!reach.canRun(query.id())) {
                out.add(entry(key, query, slot, RequestState.UNREACHABLE,
                        "앞 조달이 빈손으로 확인돼 이 조회는 돌 수 없습니다.", null, served));
                continue;
            }
            boolean gated = served.stream().anyMatch(pendingGate::contains);
            out.add(gated
                    ? entry(key, query, slot, RequestState.BLOCKED,
                            "이 조회가 필요한지는 앞 조달 결과가 정해집니다.", null, served)
                    : entry(key, query, slot, RequestState.INACTIVE, null, null, served));
        }
        return out;
    }

    /** 열어야 할 조달 하나 — 바인드가 실제로 풀리면 SQL 까지, 아니면 사유만. */
    private static DataRequest open(
            String key, QueryPool.Query query, RunSlot slot,
            Map<String, ChatDataSnapshot> rows, List<String> served) {
        if (!slot.declared() && !slot.argsPart().isEmpty()) {
            // T1: 키에서 복원한 args 로는 실행할 문장을 만들지 않는다.
            return entry(key, query, slot, RequestState.BLOCKED,
                    "선언되지 않은 절차입니다 — runs[] 로 인자 원문을 선언해 주세요.", null, served);
        }
        BindOutcome outcome = BindResolver.resolve(query, slot.args(), null, rows::get);
        return switch (outcome) {
            case BindOutcome.Ready ready -> ready(key, query, slot, ready, served);
            case BindOutcome.MissingUpstream missing -> entry(key, query, slot,
                    RequestState.BLOCKED, missing.query() + " 결과가 먼저 필요합니다.", null, served);
            case BindOutcome.EmptyUpstream empty -> entry(key, query, slot,
                    RequestState.UNREACHABLE,
                    empty.query() + " 결과가 0행이라 이어갈 수 없습니다.", null, served);
            case BindOutcome.NeedPick pick -> entry(key, query, slot, RequestState.BLOCKED,
                    pick.query() + " 결과의 " + pick.column() + " 이 여러 값입니다 — "
                            + BindResolver.candidates(pick.candidates()), null, served);
            case BindOutcome.Blocked blocked -> entry(key, query, slot,
                    RequestState.BLOCKED, blocked.reason(), null, served);
        };
    }

    private static DataRequest ready(
            String key, QueryPool.Query query, RunSlot slot,
            BindOutcome.Ready ready, List<String> served) {
        try {
            return entry(key, query, slot, RequestState.READY, null,
                    SqlRender.render(query.sql(), ready.binds()), served);
        } catch (IllegalArgumentException bad) {
            return entry(key, query, slot, RequestState.BLOCKED,
                    "조회 문장을 완성하지 못했습니다: " + bad.getMessage(), null, served);
        }
    }

    private static DataRequest entry(
            String key, QueryPool.Query query, RunSlot slot,
            RequestState state, String blocked, String sql, List<String> served) {
        return new DataRequest(key, query.label(), sql,
                sql != null ? SqlRender.columnsOf(query.sql()) : null,
                new RunDecl(slot.skill(), slot.args()), state, blocked, served);
    }

    /**
     * need 상태 보고 — 채워진 need 가 <b>우리가 시킨 조회로</b> 채워졌는지 다른 경로로
     * 왔는지를 함께 적는다. 화면과 서술이 그 둘을 구분해야 "요청한 것과 다르게 주셨는데
     * 그걸로 답했다"를 말할 수 있고, 값을 그대로 다음 조회에 박는 이상 어디서 온
     * 값인지가 추적 가능해야 한다.
     */
    private static List<RunProgress.Need> needsOf(
            NeedsResolver.Resolution resolution, List<SkillSpec.SkillNeed> spec,
            ArrivalLens lens, List<AltFillJudge.AltFill> altFills) {
        Map<String, String> alternates = lens.alternates();
        Map<String, String> judged = new LinkedHashMap<>();
        for (AltFillJudge.AltFill fill : altFills) {
            judged.put(fill.need(), fill.source());
        }
        Map<String, List<SkillSpec.Fill>> fillsById = new LinkedHashMap<>();
        for (SkillSpec.SkillNeed need : spec) {
            if (need != null && need.id() != null) {
                fillsById.put(need.id(), need.fills());
            }
        }
        List<RunProgress.Need> out = new ArrayList<>();
        for (NeedsResolver.NeedStatus n : resolution.needs()) {
            String source = judged.get(n.id());
            for (SkillSpec.Fill fill : fillsById.getOrDefault(n.id(), List.of())) {
                if (source != null || fill == null) {
                    break;
                }
                source = alternates.get(fill.query() + "." + fill.column());
            }
            out.add(new RunProgress.Need(n.id(), n.what(), n.state().name(), source));
        }
        return out;
    }

    /** 조달 id → 그것을 지목한 need id 들. 아무도 안 부르는 조달은 빈 목록(죽은 조달). */
    private static Map<String, List<String>> needsByQuery(List<SkillSpec.SkillNeed> needs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (SkillSpec.SkillNeed need : needs) {
            if (need == null || need.id() == null) {
                continue;
            }
            for (SkillSpec.Fill fill : need.fills()) {
                if (fill != null && fill.query() != null) {
                    out.computeIfAbsent(fill.query(), k -> new ArrayList<>()).add(need.id());
                }
            }
        }
        return out;
    }

    /** 이 run 의 판정 한 벌 — 도착 창구와 도달 가능성을 같은 {@code without} 으로 묶는다. */
    private static NeedsResolver.Resolution resolve(
            QueryPool pool, RunSlot slot, Map<String, SnapshotIndexEntry> index,
            Map<String, ChatDataSnapshot> rows, String without,
            List<AltFillJudge.AltFill> altFills) {
        return resolve(pool, slot, lensOf(slot, index, rows, without), altFills);
    }

    private static NeedsResolver.Resolution resolve(
            QueryPool pool, RunSlot slot, ArrivalLens lens,
            List<AltFillJudge.AltFill> altFills) {
        return NeedsResolver.resolve(pool.needsOf(slot.skill()), lens,
                NeedsResolver.reachOf(pool.wiringOf(slot.skill()), lens), valuesOf(altFills));
    }

    private static Map<String, String> valuesOf(List<AltFillJudge.AltFill> altFills) {
        Map<String, String> out = new LinkedHashMap<>();
        for (AltFillJudge.AltFill fill : altFills) {
            out.put(fill.need(), fill.value());
        }
        return out;
    }

    private static ArrivalLens lensOf(
            RunSlot slot, Map<String, SnapshotIndexEntry> index,
            Map<String, ChatDataSnapshot> rows, String without) {
        return new ArrivalLens(slot.skill(), slot.argsPart(), slot.args(), index, rows, without);
    }

    private static String keyOf(RunSlot slot, String queryId) {
        return slot.argsPart().isEmpty() ? queryId : queryId + "__" + slot.argsPart();
    }

    private static String label(RunSlot slot) {
        return slot.argsPart().isEmpty()
                ? slot.skill()
                : slot.skill() + " (" + slot.argsPart().replace("&", ", ") + ")";
    }

    // ── 서술 전이 ────────────────────────────────────────────────────────────

    /**
     * 종결 서술을 낼 것인가 — <b>이번 이벤트의 스냅샷이 그 절차를 종결로 완성시킨
     * 경우</b>에만. 서버가 "새로 terminal 이 됐다"를 기억할 수 없으므로 인과로
     * 판정한다: 이벤트 키를 빼고 다시 보면 종결이 아니어야 한다(T7). 이미 종결이던
     * 절차의 다른 항목을 만진 재시도·토글은 여기서 걸러지고, 같은 이벤트의 재전송은
     * eventId echo 로 FE 가 버린다.
     *
     * <p>종결된 절차의 스냅샷을 <b>다시 등록</b>하는 것도 인과를 통과한다 — 갱신된
     * 데이터가 절차를 다시 완성시킨 것이므로 새 서술이 맞다.
     */
    private static Narration narrationOf(
            QueryPool pool, PanelBody body,
            Map<String, SnapshotIndexEntry> index, Map<String, ChatDataSnapshot> rows,
            List<RunSlot> slots, Map<String, List<AltFillJudge.AltFill>> altFills) {
        PanelEvent event = body.event();
        if (event == null || event.queryKey() == null || event.queryKey().isBlank()) {
            return null;
        }
        String eventKey = event.queryKey().trim();
        SnapshotIndexEntry arrived = index.get(eventKey);
        if (arrived == null || !arrived.arrived()) {
            return null; // 도착이 아닌 액션(휴지통 이동·체크 해제 등)은 서술 대상이 아니다.
        }
        QueryKey.Parsed parsed = QueryKey.parse(eventKey);
        QueryPool.Query eventQuery = parsed == null ? null : pool.byId(parsed.queryId());
        if (eventQuery == null) {
            return null;
        }

        RunSlot run = null;
        for (RunSlot slot : slots) {
            if (slot.skill().equals(eventQuery.skill()) && slot.argsPart().equals(parsed.argsPart())) {
                run = slot;
                break;
            }
        }
        if (run == null) {
            return null;
        }

        List<AltFillJudge.AltFill> alt =
                altFills.getOrDefault(runKey(run.skill(), run.args(), pool), List.of());
        NeedsResolver.Resolution now = resolve(pool, run, index, rows, null, alt);
        NeedsResolver.Resolution before = resolve(pool, run, index, rows, eventKey, alt);
        if (!now.terminal() || before.terminal()) {
            return null; // 종결이 아니거나, 이 이벤트 없이도 종결이던 절차다.
        }

        List<QueryArrival> arrivals = new ArrayList<>();
        for (QueryPool.Query q : pool.queriesOf(run.skill())) {
            String key = keyOf(run, q.queryId());
            SnapshotIndexEntry hit = index.get(key);
            if (hit == null || !hit.arrived()) {
                continue;
            }
            arrivals.add(new QueryArrival(q, hit, rows.get(key)));
        }
        return new Narration(label(run), run.skill(), run.args(), List.copyOf(arrivals), now);
    }
}

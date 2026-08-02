package fdc.agent.chat;

import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.DataRequest;
import fdc.agent.contract.PanelEvent;
import fdc.agent.contract.QueryScope;
import fdc.agent.contract.RunDecl;
import fdc.agent.contract.RunProgress;
import fdc.agent.contract.SnapshotIndexEntry;
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
 * 심장). 패널의 모든 수정·입력이 이 판정을 부르고, BE 는 그 자리에서 다음 카드를
 * 직접 만든다 — 진행이 사람 발화와 LLM 받아쓰기의 확률 곱에 매달리지 않게.
 *
 * <p><b>무상태 순수함수다.</b> 판정 입력 = 도착 스냅샷 요약({@code snapshotIndex},
 * 체크된 것만 — T4) ∪ 명시된 절차 선언({@code runs[]} — T3). 서버는 아무것도
 * 기억하지 않으므로 같은 body 는 같은 판정이고(스킬 풀이 갈리면 {@code poolRev} 로
 * 드러난다 — T14), 멱등·재시도는 eventId/revision echo 로 FE 가 매듭짓는다(T7).
 *
 * <p>LLM 이 남는 자리는 <b>종결 서술뿐</b>이다. 갈림길(pick)은 LLM 좁은 질문 대신
 * 후보를 {@code needsPick} 으로 선언해 <b>사람이 고른다</b> — 조달 철학(고르되
 * 창작 금지)의 연장이고, 1차 배포(제한망 = mock 강제)에서 실질이 있는 유일한
 * 선택이다. 서술 여부도 여기서 결정론으로 판정하고({@link Verdict#narration}),
 * 문장 생성만 호출자가 LLM 에 맡긴다.
 */
public final class PanelJudge {
    private PanelJudge() {
    }

    /** 서술 요약에 싣는 단계당 행 수 상한 — 전문은 패널에 있고, 서술은 해석이다. */
    private static final int NARRATION_ROWS = 5;

    /**
     * {@code POST /api/fdc/v1/chat/data} 요청 body (demo-fe #163 짝 계약,
     * 느슨하게 수용 — 빠진 필드는 null).
     *
     * <p>{@code picks} 는 대상 조회({@code queryId}) → {컬럼: 선택값}. pick 카드에서
     * 고른 값이 상태로 남는 자리다(T8) — 응답에 그대로 echo 되고, FE 는 다음
     * 요청에 다시 싣는다.
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
            Map<String, Map<String, String>> picks) {
    }

    /** 종결 서술 지시 — 라벨과 프롬프트에 실을 도착 데이터 요약. */
    public record Narration(String runLabel, String summary) {
    }

    /** 판정 결과 — 응답 페이로드의 재료 전부. */
    public record Verdict(
            List<DataRequest> openRequests,
            List<RunProgress> runsProgress,
            List<String> terminalRuns,
            List<String> needsRows,
            Narration narration) {
    }

    public static Verdict judge(QueryPool pool, PanelBody body) {
        Map<String, SnapshotIndexEntry> index = effectiveIndex(body);
        Map<String, ChatDataSnapshot> rows = rowsByKey(body, index);
        Map<String, Map<String, String>> picks = body.picks() != null ? body.picks() : Map.of();

        List<RunSlot> slots = assembleRuns(pool, body, index);

        List<DataRequest> openRequests = new ArrayList<>();
        List<RunProgress> runsProgress = new ArrayList<>();
        List<String> terminalRuns = new ArrayList<>();
        Set<String> needsRows = new LinkedHashSet<>();

        for (RunSlot slot : slots) {
            RunProgress progress = judgeRun(pool, slot, index, rows, picks, openRequests, needsRows);
            runsProgress.add(progress);
            if (progress.terminal()) {
                terminalRuns.add(progress.label());
            }
        }

        Narration narration = narrationOf(pool, body, index, rows, slots);
        return new Verdict(List.copyOf(openRequests), List.copyOf(runsProgress),
                List.copyOf(terminalRuns), List.copyOf(needsRows), narration);
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
     * 절차 하나 — 선언({@code runs[]})이 있으면 args 는 원문이고 카드가 나갈 수 있다.
     * 도착 키에서만 유도된 미선언 run 은 진행 보고만 한다(T1 — 키는 손실 인코딩이라
     * 파싱한 args 로 SQL 을 만들지 않는다).
     */
    private record RunSlot(String skill, String argsPart, Map<String, String> args, boolean declared) {
    }

    private static List<RunSlot> assembleRuns(
            QueryPool pool, PanelBody body, Map<String, SnapshotIndexEntry> index) {
        Map<String, RunSlot> slots = new LinkedHashMap<>();

        for (RunDecl decl : body.runs() != null ? body.runs() : List.<RunDecl>of()) {
            if (decl == null || decl.skill() == null || decl.skill().isBlank()) {
                continue;
            }
            QueryPool.Query first = pool.byId(decl.skill().trim());
            Map<String, String> args = decl.args() != null ? decl.args() : Map.of();
            if (first == null) {
                // 등재되지 않은 스킬 — 무음으로 버리지 않고 보고 대상으로 남긴다.
                slots.putIfAbsent("?" + decl.skill().trim(),
                        new RunSlot(decl.skill().trim(), QueryKey.argsPart(args, List.copyOf(args.keySet())),
                                args, true));
                continue;
            }
            String argsPart = QueryKey.argsPart(args, first.requiredArgs());
            slots.putIfAbsent(first.skill() + " " + argsPart,
                    new RunSlot(first.skill(), argsPart, args, true));
        }

        for (String key : index.keySet()) {
            QueryKey.Parsed parsed = QueryKey.parse(key);
            QueryPool.Query query =
                    parsed == null ? null : pool.byId(parsed.skill() + "#" + parsed.step());
            if (query == null) {
                continue; // 풀 밖 키(자유 저작 스냅샷) — 진행으로 읽지 않는다.
            }
            slots.putIfAbsent(query.skill() + " " + parsed.argsPart(),
                    new RunSlot(query.skill(), parsed.argsPart(),
                            QueryKey.parseArgs(parsed.argsPart()), false));
        }
        return List.copyOf(slots.values());
    }

    // ── run 하나의 판정 ──────────────────────────────────────────────────────

    private static RunProgress judgeRun(
            QueryPool pool, RunSlot slot,
            Map<String, SnapshotIndexEntry> index, Map<String, ChatDataSnapshot> rows,
            Map<String, Map<String, String>> picks,
            List<DataRequest> openRequests, Set<String> needsRows) {
        List<QueryPool.Query> steps = pool.stepsOf(slot.skill());
        String label = label(slot);
        if (steps.isEmpty()) {
            return new RunProgress(slot.skill(), slot.args(), label, 0, 0, -1, false, null, null,
                    List.of(new RunProgress.StepHold(slot.skill(), "등재되지 않은 스킬입니다.")));
        }

        int arrivedCount = 0;
        int nextStep = -1;
        Integer emptyAt = null;
        for (QueryPool.Query q : steps) {
            SnapshotIndexEntry hit = index.get(stepKey(slot, q));
            boolean arrived = hit != null && hit.arrived();
            if (arrived) {
                arrivedCount++;
                if (emptyAt == null && hit.isEmptyResult()) {
                    emptyAt = q.step();
                }
            } else if (nextStep < 0) {
                nextStep = q.step();
            }
        }
        boolean terminal = nextStep < 0 || emptyAt != null;

        List<RunProgress.PickNeed> needsPick = new ArrayList<>();
        List<RunProgress.StepHold> holds = new ArrayList<>();
        if (!terminal) {
            if (!slot.declared() && !slot.argsPart().isEmpty()) {
                // T1: 키에서 복원한 args 로는 SQL 을 만들지 않는다 — 선언을 요구한다.
                holds.add(new RunProgress.StepHold(slot.skill(),
                        "선언되지 않은 절차 — 카드를 만들려면 runs[] 로 인자 원문을 선언하세요."));
            } else {
                for (QueryPool.Query q : steps) {
                    SnapshotIndexEntry hit = index.get(stepKey(slot, q));
                    if (hit != null && hit.arrived()) {
                        continue;
                    }
                    stepCard(slot, q, index, rows, picks, openRequests, needsRows, needsPick, holds);
                }
            }
        }

        return new RunProgress(slot.skill(), slot.args(), label, steps.size(), arrivedCount,
                nextStep, terminal, emptyAt,
                needsPick.isEmpty() ? null : List.copyOf(needsPick),
                holds.isEmpty() ? null : List.copyOf(holds));
    }

    /**
     * 미도착 스텝 하나를 카드로 만들 수 있나 — 바인드가 준비된 스텝 <b>전부</b>가
     * 카드가 된다(다음 하나만 광고하던 채팅 릴레이의 직렬화 갭 해소). 못 만들면
     * pick 필요/보류로 보고하고, 순서상 이른 것(앞 단계 미도착)만 무음으로 넘긴다.
     */
    private static void stepCard(
            RunSlot slot, QueryPool.Query q,
            Map<String, SnapshotIndexEntry> index, Map<String, ChatDataSnapshot> rows,
            Map<String, Map<String, String>> picks,
            List<DataRequest> openRequests, Set<String> needsRows,
            List<RunProgress.PickNeed> needsPick, List<RunProgress.StepHold> holds) {
        // T2: 선택 인자를 바인드로 쓰는 스텝은 선언 경로로 완성할 수 없다 — 카드를
        // 만들지 않고 채팅(릴레이) 경로에 남긴다. 조용한 멈춤 대신 사유를 적는다.
        for (SkillSpec.BindSource src : q.binds().values()) {
            if ("arg".equals(src.from()) && !q.requiredArgs().contains(src.arg())) {
                holds.add(new RunProgress.StepHold(q.queryId(),
                        "선택 인자(" + src.arg() + ") 바인드 — 이 단계는 채팅 경로로만 나갑니다."));
                return;
            }
        }

        // 앞 단계 의존: 도착 여부는 판정 집합(index)이 진실이고, 값 추출에만 rows 가
        // 필요하다 — 도착했는데 rows 가 안 실려 왔으면 재호출을 요구한다(T16).
        for (SkillSpec.BindSource src : q.binds().values()) {
            if (!"step".equals(src.from()) || src.step() == null) {
                continue;
            }
            String upstreamKey = QueryKey.of(slot.skill(), src.step(), slot.args(), q.requiredArgs());
            SnapshotIndexEntry upstream = index.get(upstreamKey);
            if (upstream == null || !upstream.arrived() || upstream.isEmptyResult()) {
                return; // 순서상 이르다(또는 0행 — terminal 판정이 이미 다뤘다).
            }
            if (!rows.containsKey(upstreamKey)) {
                needsRows.add(upstreamKey);
                return;
            }
        }

        BindOutcome outcome = BindResolver.resolve(
                q, slot.args(), pickFor(picks, q.queryId()), rows::get);
        switch (outcome) {
            case BindOutcome.Ready ready -> {
                try {
                    String sql = SqlRender.render(q.sql(), ready.binds());
                    openRequests.add(new DataRequest(
                            QueryKey.of(slot.skill(), q.step(), slot.args(), q.requiredArgs()),
                            q.title() + DataRequestTool.argsSuffix(slot.args(), q.requiredArgs()),
                            sql, SqlRender.columnsOf(q.sql())));
                } catch (IllegalArgumentException bad) {
                    holds.add(new RunProgress.StepHold(q.queryId(),
                            "조회 문장을 완성하지 못했습니다: " + bad.getMessage()));
                }
            }
            case BindOutcome.NeedPick pick ->
                    needsPick.add(new RunProgress.PickNeed(q.queryId(), pick.column(), pick.candidates()));
            case BindOutcome.MissingUpstream missing -> {
                // index 선확인과 어긋나는 경우는 없어야 하나, 있어도 무음이 아니라 순서다.
            }
            case BindOutcome.EmptyUpstream empty -> {
                // terminal 판정이 다뤘다 — 여기 오면 판정 집합과 rows 가 어긋난 것. 넘긴다.
            }
            case BindOutcome.Blocked blocked ->
                    holds.add(new RunProgress.StepHold(q.queryId(), blocked.reason()));
        }
    }

    private static Map<String, String> pickFor(Map<String, Map<String, String>> picks, String queryId) {
        Map<String, String> exact = picks.get(queryId);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Map<String, String>> e : picks.entrySet()) {
            if (e.getKey() != null && normalize(e.getKey()).equals(normalize(queryId))) {
                return e.getValue();
            }
        }
        return Map.of();
    }

    private static String normalize(String id) {
        return id.replace('-', '_').toLowerCase();
    }

    private static String stepKey(RunSlot slot, QueryPool.Query q) {
        return slot.argsPart().isEmpty() ? q.queryId() : q.queryId() + "__" + slot.argsPart();
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
            List<RunSlot> slots) {
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
        QueryPool.Query eventQuery =
                parsed == null ? null : pool.byId(parsed.skill() + "#" + parsed.step());
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

        List<QueryPool.Query> steps = pool.stepsOf(run.skill());
        if (!terminalWith(steps, run, index, null) || terminalWith(steps, run, index, eventKey)) {
            return null; // 종결이 아니거나, 이 이벤트 없이도 종결이던 절차다.
        }
        return new Narration(label(run), summary(steps, run, index, rows));
    }

    /** {@code without} 키를 미도착으로 치고 본 종결 여부. */
    private static boolean terminalWith(
            List<QueryPool.Query> steps, RunSlot run,
            Map<String, SnapshotIndexEntry> index, String without) {
        boolean allArrived = true;
        for (QueryPool.Query q : steps) {
            String key = stepKey(run, q);
            SnapshotIndexEntry hit = key.equals(without) ? null : index.get(key);
            if (hit == null || !hit.arrived()) {
                allArrived = false;
            } else if (hit.isEmptyResult()) {
                return true;
            }
        }
        return allArrived;
    }

    /** 서술 프롬프트에 실을 도착 데이터 요약 — 단계별 결과와 값 일부. */
    private static String summary(
            List<QueryPool.Query> steps, RunSlot run,
            Map<String, SnapshotIndexEntry> index, Map<String, ChatDataSnapshot> rows) {
        List<String> lines = new ArrayList<>();
        lines.add(ChatPrompt.SECTION_ARRIVED);
        lines.add("절차: " + label(run));
        for (QueryPool.Query q : steps) {
            String key = stepKey(run, q);
            SnapshotIndexEntry hit = index.get(key);
            if (hit == null || !hit.arrived()) {
                continue;
            }
            if (hit.isEmptyResult()) {
                lines.add("- " + q.title() + ": 조회 결과 0행 — 데이터가 없음이 확인됐다.");
                continue;
            }
            ChatDataSnapshot full = rows.get(key);
            String cols = full != null && full.columns() != null
                    ? " (" + String.join(", ", full.columns()) + ")"
                    : hit.columns() != null ? " (" + String.join(", ", hit.columns()) + ")" : "";
            lines.add("- " + q.title() + ": " + hit.rowCount() + "행" + cols);
            if (full != null && full.hasRows()) {
                List<List<String>> sample = full.rows().size() > NARRATION_ROWS
                        ? full.rows().subList(0, NARRATION_ROWS)
                        : full.rows();
                for (List<String> row : sample) {
                    lines.add("  " + String.join(" | ", row.stream()
                            .map(c -> c != null ? c : "(null)").toList()));
                }
                if (full.rows().size() > sample.size()) {
                    lines.add("  … 외 " + (full.rows().size() - sample.size()) + "행 (패널에 전문)");
                }
            }
        }
        return String.join("\n", lines);
    }
}

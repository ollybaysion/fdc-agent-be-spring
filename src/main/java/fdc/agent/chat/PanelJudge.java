package fdc.agent.chat;

import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.PanelEvent;
import fdc.agent.contract.QueryScope;
import fdc.agent.contract.RunDecl;
import fdc.agent.contract.RunProgress;
import fdc.agent.contract.SnapshotIndexEntry;
import fdc.agent.skills.QueryPool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * panel-judge — 데이터 패널 상태의 <b>결정론 판정</b> (#38, {@code POST /chat/data} 의
 * 심장). 패널의 모든 수정·입력이 이 판정을 부르고, BE 는 절차의 진행·종결을
 * 인지한다. <b>요청 카드는 여기서 만들지 않는다</b> — 카드 배치·SQL 완성은 FE 가
 * 카탈로그(binds 포함)로 로컬 판정한다(demo-fe dataList, #169). 이 왕복의 용도는
 * BE 인지와 종결 서술 전이다.
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
            QueryScope scope) {
    }

    /**
     * 종결 서술 지시 — run 정체와 도착 실물 전부. 판정기는 <b>무엇이 도착해 절차가
     * 끝났는가</b>만 내놓고, 문장(맥락 섹션) 합성은 {@link NarrationPrompt} 소관이다.
     */
    public record Narration(
            String runLabel, String skill, Map<String, String> args, List<StepArrival> steps) {
    }

    /**
     * 도착한 스텝 하나 — 판정 집합의 요약({@code hit})과 행 실물({@code full}).
     * {@code full} 은 0행이거나 이 요청에 rows 가 안 실렸으면 null 일 수 있다.
     */
    public record StepArrival(
            QueryPool.Query query, SnapshotIndexEntry hit, ChatDataSnapshot full) {
    }

    /** 판정 결과 — 응답 페이로드의 재료 전부. */
    public record Verdict(
            List<RunProgress> runsProgress,
            List<String> terminalRuns,
            Narration narration) {
    }

    public static Verdict judge(QueryPool pool, PanelBody body) {
        Map<String, SnapshotIndexEntry> index = effectiveIndex(body);
        Map<String, ChatDataSnapshot> rows = rowsByKey(body, index);

        List<RunSlot> slots = assembleRuns(pool, body, index);

        List<RunProgress> runsProgress = new ArrayList<>();
        List<String> terminalRuns = new ArrayList<>();
        for (RunSlot slot : slots) {
            RunProgress progress = judgeRun(pool, slot, index);
            runsProgress.add(progress);
            if (progress.terminal()) {
                terminalRuns.add(progress.label());
            }
        }

        Narration narration = narrationOf(pool, body, index, rows, slots);
        return new Verdict(List.copyOf(runsProgress), List.copyOf(terminalRuns), narration);
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
     * args 는 키 파싱 복원이다. 어느 쪽이든 판정은 SQL 을 만들지 않으므로(카드는
     * FE 로컬 판정) 복원 args 는 라벨·서술 문장에만 쓰인다.
     */
    private record RunSlot(String skill, String argsPart, Map<String, String> args) {
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
                                args));
                continue;
            }
            String argsPart = QueryKey.argsPart(args, first.requiredArgs());
            slots.putIfAbsent(first.skill() + " " + argsPart,
                    new RunSlot(first.skill(), argsPart, args));
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
                            QueryKey.parseArgs(parsed.argsPart())));
        }
        return List.copyOf(slots.values());
    }

    // ── run 하나의 판정 ──────────────────────────────────────────────────────

    private static RunProgress judgeRun(
            QueryPool pool, RunSlot slot, Map<String, SnapshotIndexEntry> index) {
        List<QueryPool.Query> steps = pool.stepsOf(slot.skill());
        String label = label(slot);
        if (steps.isEmpty()) {
            return new RunProgress(slot.skill(), slot.args(), label, 0, 0, -1, false, null,
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

        return new RunProgress(slot.skill(), slot.args(), label, steps.size(), arrivedCount,
                nextStep, terminal, emptyAt, null);
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
        List<StepArrival> arrivals = new ArrayList<>();
        for (QueryPool.Query q : steps) {
            String key = stepKey(run, q);
            SnapshotIndexEntry hit = index.get(key);
            if (hit == null || !hit.arrived()) {
                continue;
            }
            arrivals.add(new StepArrival(q, hit, rows.get(key)));
        }
        return new Narration(label(run), run.skill(), run.args(), List.copyOf(arrivals));
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

}

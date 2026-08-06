package fdc.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.PanelJudge.PanelBody;
import fdc.agent.chat.PanelJudge.Verdict;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.DataRequest;
import fdc.agent.contract.PanelEvent;
import fdc.agent.contract.RequestState;
import fdc.agent.contract.RunDecl;
import fdc.agent.contract.RunProgress;
import fdc.agent.contract.SnapshotIndexEntry;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * panel-judge 판정(#38) — 무상태 순수함수: 선언 ∪ 도착에서 진행·종결 인지와
 * 서술 전이가 결정론으로 나온다. 요청 카드는 판정 소관이 아니다(FE 로컬 판정).
 *
 * <p>v3 에서 종결의 기준이 "조회가 다 왔나"에서 "알아야 할 걸 다 알았나"로 바뀌었다.
 */
class PanelJudgeTest {

    private static final String BASE = "t-two-step#base_row__id=X-1";
    private static final String DETAIL = "t-two-step#detail_row__id=X-1";

    /** 조달 둘 — detail 의 바인드가 base 결과 컬럼 A 에서 나온다. */
    private static SkillSpec twoStep() {
        return new SkillSpec("t-two-step", "{id}", List.of("X-1 뭐야?"),
                "X-1 의 기준과 상세.", null,
                List.of(new SkillSpec.SkillInput("id", true, "조회 키")),
                null,
                List.of(
                        new SkillSpec.SkillNeed("base", "기준", null,
                                List.of(new SkillSpec.Fill("base_row", "A"))),
                        new SkillSpec.SkillNeed("detail", "상세", null,
                                List.of(new SkillSpec.Fill("detail_row", "C")))),
                List.of(
                        new SkillSpec.SpecQuery("base_row", "sql", "t0",
                                "SELECT A, B FROM t0 WHERE id = :id",
                                Map.of("id", new SkillSpec.BindSource("arg", "id", null, null)),
                                null),
                        new SkillSpec.SpecQuery("detail_row", "sql", "t1",
                                "SELECT C FROM t1 WHERE a = :a",
                                Map.of("a", new SkillSpec.BindSource("query", null, "base_row", "A")),
                                null)),
                null, null);
    }

    private static QueryPool pool() {
        return QueryPool.of(List.of(twoStep()));
    }

    private static PanelBody body(
            PanelEvent event, List<SnapshotIndexEntry> index, List<ChatDataSnapshot> snapshots,
            List<RunDecl> runs) {
        return new PanelBody("e1", 1, event, null, index, snapshots, runs, null, null);
    }

    private static List<RunDecl> declared() {
        return List.of(new RunDecl("t-two-step", Map.of("id", "X-1")));
    }

    private static ChatDataSnapshot baseRow(String capturedAt, List<List<String>> rows) {
        return new ChatDataSnapshot(BASE, "기준", capturedAt, List.of("A", "B"), rows.size(), rows);
    }

    private static ChatDataSnapshot detailRow(String capturedAt, List<List<String>> rows) {
        return new ChatDataSnapshot(DETAIL, "상세", capturedAt, List.of("C"), rows.size(), rows);
    }

    @Test
    void 선언만_있어도_절차가_진행으로_보고된다() {
        // T3: run 은 도착에서만 유도되지 않는다 — 선언이 절차의 시작을 연다.
        Verdict v = PanelJudge.judge(pool(), body(null, null, null, declared()));

        assertThat(v.runsProgress()).hasSize(1);
        RunProgress run = v.runsProgress().get(0);
        assertThat(run.label()).isEqualTo("t-two-step (id=X-1)");
        assertThat(run.outcome()).isEqualTo("PROCURABLE");
        assertThat(run.metCount()).isZero();
        assertThat(run.needCount()).isEqualTo(2);
        assertThat(run.terminal()).isFalse();
    }

    @Test
    void 조달이_도착하면_그_need_가_찬다() {
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(baseRow("2026-08-01T00:00", List.of(List.of("a1", "b1")))),
                declared()));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.metCount()).isEqualTo(1);
        assertThat(run.wanted()).containsExactly("t-two-step#detail_row");
        assertThat(run.terminal()).isFalse();
    }

    @Test
    void 체크_해제된_스냅샷은_판정_밖이다() {
        // T4: 판정 집합은 패널 전체가 아니라 사용자가 켜 둔 것이다.
        Verdict v = PanelJudge.judge(pool(), body(null,
                List.of(new SnapshotIndexEntry(BASE, "기준", "2026-08-01T00:00",
                        List.of("A", "B"), 1, "h1", false)),
                null, declared()));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.metCount()).isZero(); // 도착하지 않은 것으로 판정.
    }

    @Test
    void 행_실물이_없으면_찼다고만_하고_값은_모른다() {
        // T16 경량 판정: 요약만 와도 채워짐은 판정되지만 값은 못 읽는다.
        Verdict v = PanelJudge.judge(pool(), body(null,
                List.of(new SnapshotIndexEntry(BASE, "기준", "2026-08-01T00:00",
                        List.of("A", "B"), 1, "h1", true)),
                null, declared()));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.metCount()).isEqualTo(1);
        assertThat(run.needs().get(0).state()).isEqualTo("FILLED");
    }

    @Test
    void 같은_키_다중_도착은_capturedAt_최신_한_건만_본다() {
        // T9: 배열 순서 last-wins 가 아니라 시각 규칙 — 서술에 실리는 행도 최신 것이다.
        List<ChatDataSnapshot> arrived = List.of(
                baseRow("2026-08-01T02:00", List.of(List.of("a2", "b2"))),
                baseRow("2026-08-01T01:00", List.of(List.of("a1", "b1"))),
                detailRow("2026-08-01T03:00", List.of(List.of("c1"))));

        Verdict v = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-registered", DETAIL), null, arrived, declared()));

        assertThat(v.narration()).isNotNull();
        assertThat(v.narration().arrivals().get(0).full().rows().get(0))
                .containsExactly("a2", "b2");
    }

    @Test
    void 영행_확인이면_종결이다() {
        // 0행도 도착이고, 그 값으로 이어가는 조달은 영영 못 돈다 — 답불가로 끝난다.
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(baseRow("2026-08-01T00:00", List.of())), declared()));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.terminal()).isTrue();
        assertThat(run.outcome()).isEqualTo("UNANSWERABLE");
        assertThat(run.needs()).allSatisfy(n -> assertThat(n.state()).isEqualTo("UNPROCURABLE"));
        assertThat(v.terminalRuns()).containsExactly(run.label());
    }

    @Test
    void 미선언_절차도_도착_키에서_유도돼_진행이_보고된다() {
        // 키 파싱으로 복원한 args 는 라벨·서술 문장에만 쓰인다 — SQL 은 만들지 않는다.
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(baseRow("2026-08-01T00:00", List.of(List.of("a1", "b1")))), null));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.label()).isEqualTo("t-two-step (id=X-1)");
        assertThat(run.metCount()).isEqualTo(1);
    }

    @Test
    void 등재되지_않은_스킬_선언은_사유를_보고한다() {
        Verdict v = PanelJudge.judge(pool(), body(null, null, null,
                List.of(new RunDecl("no-such-skill", Map.of()))));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.outcome()).isEqualTo("UNKNOWN_SKILL");
        assertThat(run.holds().get(0).reason()).contains("등재되지 않은");
    }

    @Test
    void 이벤트가_절차를_종결로_완성시키면_서술이_난다() {
        // T7: "새로 terminal" 을 상태 없이 인과로 판정 — 이 이벤트를 빼면 종결이 아니어야 한다.
        Verdict v = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-registered", BASE), null,
                List.of(baseRow("2026-08-01T00:00", List.of())), declared()));

        assertThat(v.narration()).isNotNull();
        assertThat(v.narration().runLabel()).isEqualTo("t-two-step (id=X-1)");
        assertThat(v.narration().skill()).isEqualTo("t-two-step");
        assertThat(v.narration().args()).isEqualTo(Map.of("id", "X-1"));
        // 도착 실물 — 0행 하나. 문장 합성은 NarrationPrompt 소관이라 여기 없다.
        assertThat(v.narration().arrivals()).hasSize(1);
        assertThat(v.narration().arrivals().get(0).query().queryId())
                .isEqualTo("t-two-step#base_row");
        assertThat(v.narration().arrivals().get(0).hit().isEmptyResult()).isTrue();
        // 못 채운 것이 남은 채로 끝난 것도 종결이고, 서술은 그 목록을 받는다.
        assertThat(v.narration().resolution().unmet()).hasSize(2);
    }

    @Test
    void 이미_종결이던_절차의_다른_변경은_서술이_없다() {
        // base 가 0행이라 절차는 이미 끝났다 — detail 은 애초에 돌 수 없었으므로,
        // 그걸 다시 등록해도 종결을 새로 만든 게 아니다.
        List<ChatDataSnapshot> arrived = List.of(
                baseRow("2026-08-01T00:00", List.of()),
                detailRow("2026-08-01T00:10", List.of()));

        Verdict onDetail = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-registered", DETAIL), null, arrived, declared()));
        assertThat(onDetail.narration()).isNull();

        Verdict onBase = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-updated", BASE), null, arrived, declared()));
        assertThat(onBase.narration()).isNotNull();
    }

    // ── 조달 원장 ────────────────────────────────────────────────────────────

    private static DataRequest ledgerOf(Verdict v, String queryKey) {
        return v.ledger().stream()
                .filter(r -> r.queryKey().equals(queryKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("원장에 없다: " + queryKey));
    }

    @Test
    void 원장은_조달_전량을_상태와_함께_싣는다() {
        // 잠긴 것도 실린다 — 무엇을 감출지는 화면이 판단하지 않는다.
        Verdict v = PanelJudge.judge(pool(), body(null, null, null, declared()));

        assertThat(v.ledger()).hasSize(2);
        DataRequest base = ledgerOf(v, BASE);
        assertThat(base.state()).isEqualTo(RequestState.READY);
        assertThat(base.sql()).isEqualTo("SELECT A, B FROM t0 WHERE id = 'X-1'");
        assertThat(base.needs()).containsExactly("base");
        assertThat(base.run()).isEqualTo(new RunDecl("t-two-step", Map.of("id", "X-1")));

        DataRequest detail = ledgerOf(v, DETAIL);
        assertThat(detail.state()).isEqualTo(RequestState.BLOCKED);
        assertThat(detail.sql()).isNull();
        assertThat(detail.blocked()).contains("base_row");
    }

    @Test
    void 도착하면_원장이_다음_조달을_연다() {
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(baseRow("2026-08-01T00:00", List.of(List.of("a1", "b1")))),
                declared()));

        assertThat(ledgerOf(v, BASE).state()).isEqualTo(RequestState.ARRIVED);
        DataRequest detail = ledgerOf(v, DETAIL);
        assertThat(detail.state()).isEqualTo(RequestState.READY);
        assertThat(detail.sql()).isEqualTo("SELECT C FROM t1 WHERE a = 'a1'");
    }

    @Test
    void 상류가_영행이면_잠김이_아니라_도달불가다() {
        // 기다리면 열리는 것(BLOCKED)과 영영 안 열리는 것(UNREACHABLE)은 다른 사실이다.
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(baseRow("2026-08-01T00:00", List.of())), declared()));

        assertThat(ledgerOf(v, BASE).state()).isEqualTo(RequestState.ARRIVED);
        assertThat(ledgerOf(v, DETAIL).state()).isEqualTo(RequestState.UNREACHABLE);
    }

    @Test
    void 미선언_절차는_원장에_SQL_을_올리지_않는다() {
        // T1: 키는 손실 인코딩이라 복원한 args 로 사람이 실행할 문장을 만들지 않는다.
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(baseRow("2026-08-01T00:00", List.of(List.of("a1", "b1")))), null));

        DataRequest detail = ledgerOf(v, DETAIL);
        assertThat(detail.state()).isEqualTo(RequestState.BLOCKED);
        assertThat(detail.sql()).isNull();
        assertThat(detail.blocked()).contains("선언되지 않은");
    }

    @Test
    void 같은_상태는_같은_원장이다() {
        // 원장은 이벤트가 아니라 상태 전량 — 멱등이 규칙이 아니라 구조다.
        List<ChatDataSnapshot> arrived =
                List.of(baseRow("2026-08-01T00:00", List.of(List.of("a1", "b1"))));

        assertThat(PanelJudge.judge(pool(), body(null, null, arrived, declared())).ledger())
                .isEqualTo(PanelJudge.judge(pool(), body(null, null, arrived, declared())).ledger());
    }

    // ── 2차 채움(다른 경로로 온 같은 사실) ───────────────────────────────────

    @Test
    void 같은_스킬의_다른_조달에_동명_컬럼이_있어도_끌어오지_않는다() {
        // spec 은 need 마다 (조달, 컬럼)을 못 박았다. fdc_sensor.USE_YN(센서가 쓰이나)과
        // fdc_equipment.USE_YN(설비가 쓰이나)처럼 이름만 같고 뜻이 다른 컬럼을 이으면
        // 조용히 틀린 사실이 답으로 간다.
        ChatDataSnapshot wide = new ChatDataSnapshot(BASE, "기준", "2026-08-01T00:00",
                List.of("A", "B", "C"), 1, List.of(List.of("a1", "b1", "c9")));

        Verdict v = PanelJudge.judge(pool(), body(null, null, List.of(wide), declared()));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.metCount()).isEqualTo(1);
        assertThat(run.needs().get(1).state()).isEqualTo("UNFILLED");
        assertThat(run.needs().get(1).source()).isNull();
        // 그 조회는 여전히 열려 있어야 한다 — 안 그러면 영영 못 채운다.
        assertThat(ledgerOf(v, DETAIL).state()).isEqualTo(RequestState.READY);
    }

    @Test
    void 도착한_영행은_2차로_뒤집히지_않는다() {
        // 0행은 미도착이 아니라 "없다"는 사실 — 다른 표에 값이 보여도 그 사실이 이긴다.
        ChatDataSnapshot emptyDetail =
                new ChatDataSnapshot(DETAIL, "상세", "2026-08-01T00:10", List.of("C"), 0, List.of());
        ChatDataSnapshot pasted = new ChatDataSnapshot("붙여넣은-표", "내 표", "2026-08-01T00:00",
                List.of("id", "C"), 1, List.of(List.of("X-1", "c9")));

        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(baseRow("2026-08-01T00:00", List.of(List.of("a1", "b1"))),
                        pasted, emptyDetail),
                declared()));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.needs().get(1).state()).isEqualTo("UNPROCURABLE");
        assertThat(run.needs().get(1).source()).isNull();
    }

    @Test
    void 자유_저작_표는_인자가_맞는_행에서만_읽는다() {
        // 이름표가 없는 표는 행 안에서 대조한다 — 표 전체를 믿으면 남의 설비 값이 들어온다.
        ChatDataSnapshot pasted = new ChatDataSnapshot("붙여넣은-표", "내 표", "2026-08-01T00:00",
                List.of("id", "C"), 2,
                List.of(List.of("X-9", "남의값"), List.of("X-1", "내값")));

        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(baseRow("2026-08-01T00:00", List.of(List.of("a1", "b1"))), pasted),
                declared()));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.needs().get(1).state()).isEqualTo("FILLED");
        assertThat(run.needs().get(1).source()).isEqualTo("붙여넣은-표");
        // 이미 아는 사실이라 그 조회는 다시 시키지 않는다.
        assertThat(ledgerOf(v, DETAIL).state()).isEqualTo(RequestState.INACTIVE);
    }

    @Test
    void 대조할_인자가_없는_표는_2차로_안_쓴다() {
        ChatDataSnapshot pasted = new ChatDataSnapshot("붙여넣은-표", "내 표", "2026-08-01T00:00",
                List.of("C"), 1, List.of(List.of("어디_것인지_모름")));

        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(baseRow("2026-08-01T00:00", List.of(List.of("a1", "b1"))), pasted),
                declared()));

        assertThat(v.runsProgress().get(0).needs().get(1).state()).isEqualTo("UNFILLED");
    }

    @Test
    void 도착이_아닌_이벤트나_풀_밖_키는_서술_대상이_아니다() {
        Verdict noEvent = PanelJudge.judge(pool(), body(null, null,
                List.of(baseRow("2026-08-01T00:00", List.of())), declared()));
        assertThat(noEvent.narration()).isNull();

        Verdict strangeKey = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-registered", "자유-저작-키"), null,
                List.of(baseRow("2026-08-01T00:00", List.of())), declared()));
        assertThat(strangeKey.narration()).isNull();
    }
}

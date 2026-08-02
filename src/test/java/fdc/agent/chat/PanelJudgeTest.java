package fdc.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.PanelJudge.PanelBody;
import fdc.agent.chat.PanelJudge.Verdict;
import fdc.agent.contract.BranchDecision;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.PanelEvent;
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
 */
class PanelJudgeTest {

    private static final String KEY0 = "t-two-step#0__id=X-1";
    private static final String KEY1 = "t-two-step#1__id=X-1";

    /** 2단계 스킬 — 2단계 바인드가 1단계 결과 컬럼 A 에서 나온다. */
    private static SkillSpec twoStep() {
        return new SkillSpec("t-two-step", null, null, null, null, null,
                List.of(new SkillSpec.SkillInput("id", true, "조회 키")),
                null,
                List.of(
                        new SkillSpec.SkillStep("1단계 — 기준", null, "기준", null,
                                "SELECT A, B FROM t0 WHERE id = :id",
                                Map.of("id", new SkillSpec.BindSource("arg", "id", null, null)),
                                null, null),
                        new SkillSpec.SkillStep("2단계 — 상세", null, "상세", null,
                                "SELECT C FROM t1 WHERE a = :a",
                                Map.of("a", new SkillSpec.BindSource("step", null, 0, "A")),
                                null, null)),
                null, null);
    }

    private static QueryPool pool() {
        return QueryPool.of(List.of(twoStep()));
    }

    private static PanelBody body(
            PanelEvent event, List<SnapshotIndexEntry> index, List<ChatDataSnapshot> snapshots,
            List<RunDecl> runs) {
        return new PanelBody("e1", 1, event, null, index, snapshots, runs, null, null, null);
    }

    private static List<RunDecl> declared() {
        return List.of(new RunDecl("t-two-step", Map.of("id", "X-1")));
    }

    private static ChatDataSnapshot step0(String capturedAt, List<List<String>> rows) {
        return new ChatDataSnapshot(KEY0, "1단계", capturedAt, List.of("A", "B"),
                rows.size(), rows);
    }

    @Test
    void 선언만_있어도_절차가_진행으로_보고된다() {
        // T3: run 은 도착에서만 유도되지 않는다 — 선언이 절차의 시작을 연다.
        Verdict v = PanelJudge.judge(pool(), body(null, null, null, declared()));

        assertThat(v.runsProgress()).hasSize(1);
        assertThat(v.runsProgress().get(0).label()).isEqualTo("t-two-step (id=X-1)");
        assertThat(v.runsProgress().get(0).nextStep()).isEqualTo(0);
        assertThat(v.runsProgress().get(0).terminal()).isFalse();
    }

    @Test
    void 단계가_도착하면_진행이_갱신된다() {
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(step0("2026-08-01T00:00", List.of(List.of("a1", "b1")))),
                declared()));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.arrivedCount()).isEqualTo(1);
        assertThat(run.nextStep()).isEqualTo(1);
        assertThat(run.terminal()).isFalse();
    }

    @Test
    void 체크_해제된_스냅샷은_판정_밖이다() {
        // T4: 판정 집합은 패널 전체가 아니라 사용자가 켜 둔 것이다.
        Verdict v = PanelJudge.judge(pool(), body(null,
                List.of(new SnapshotIndexEntry(KEY0, "1단계", "2026-08-01T00:00",
                        List.of("A", "B"), 1, "h1", false)),
                null, declared()));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.arrivedCount()).isZero(); // 1단계가 도착하지 않은 것으로 판정.
        assertThat(run.nextStep()).isZero();
    }

    @Test
    void 같은_키_다중_도착은_capturedAt_최신_한_건만_본다() {
        // T9: 배열 순서 last-wins 가 아니라 시각 규칙 — 서술에 실리는 행도 최신 것이다.
        List<ChatDataSnapshot> arrived = List.of(
                step0("2026-08-01T02:00", List.of(List.of("a2", "b2"))),
                step0("2026-08-01T01:00", List.of(List.of("a1", "b1"))),
                new ChatDataSnapshot(KEY1, "2단계", "2026-08-01T03:00", List.of("C"), 1,
                        List.of(List.of("c1"))));

        Verdict v = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-registered", KEY1), null, arrived, declared()));

        assertThat(v.narration()).isNotNull();
        assertThat(v.narration().steps().get(0).full().rows().get(0))
                .containsExactly("a2", "b2");
    }

    @Test
    void 영행_확인이면_종결이다() {
        // T5: 0행도 도착이고 종결이다 — 없다는 사실로 절차가 끝난다.
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(step0("2026-08-01T00:00", List.of())),
                declared()));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.terminal()).isTrue();
        assertThat(run.emptyAtStep()).isEqualTo(0);
        assertThat(v.terminalRuns()).containsExactly(run.label());
    }

    @Test
    void 미선언_절차도_도착_키에서_유도돼_진행이_보고된다() {
        // 키 파싱으로 복원한 args 는 라벨·서술 문장에만 쓰인다 — SQL 은 만들지 않는다.
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(step0("2026-08-01T00:00", List.of(List.of("a1", "b1")))),
                null));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.label()).isEqualTo("t-two-step (id=X-1)");
        assertThat(run.arrivedCount()).isEqualTo(1);
    }

    @Test
    void 등재되지_않은_스킬_선언은_사유를_보고한다() {
        Verdict v = PanelJudge.judge(pool(), body(null, null, null,
                List.of(new RunDecl("no-such-skill", Map.of()))));

        assertThat(v.runsProgress().get(0).holds().get(0).reason()).contains("등재되지 않은");
    }

    @Test
    void 이벤트가_절차를_종결로_완성시키면_서술이_난다() {
        // T7: "새로 terminal" 을 상태 없이 인과로 판정 — 이 이벤트를 빼면 종결이 아니어야 한다.
        Verdict v = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-registered", KEY0), null,
                List.of(step0("2026-08-01T00:00", List.of())),
                declared()));

        assertThat(v.narration()).isNotNull();
        assertThat(v.narration().runLabel()).isEqualTo("t-two-step (id=X-1)");
        assertThat(v.narration().skill()).isEqualTo("t-two-step");
        assertThat(v.narration().args()).isEqualTo(Map.of("id", "X-1"));
        // 도착 실물 — 0행 1단계 하나. 문장 합성은 NarrationPrompt 소관이라 여기 없다.
        assertThat(v.narration().steps()).hasSize(1);
        assertThat(v.narration().steps().get(0).query().queryId()).isEqualTo("t-two-step#0");
        assertThat(v.narration().steps().get(0).hit().isEmptyResult()).isTrue();
    }

    @Test
    void 이미_종결이던_절차의_다른_변경은_서술이_없다() {
        // 2단계 0행이 이미 절차를 끝냈다 — 1단계 재등록 이벤트는 종결을 새로 만든 게 아니다.
        List<ChatDataSnapshot> arrived = List.of(
                step0("2026-08-01T00:00", List.of(List.of("a1", "b1"))),
                new ChatDataSnapshot(KEY1, "2단계", "2026-08-01T00:10", List.of("C"), 0, List.of()));

        Verdict onStep0 = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-updated", KEY0), null, arrived, declared()));
        assertThat(onStep0.narration()).isNull();

        Verdict onStep1 = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-registered", KEY1), null, arrived, declared()));
        assertThat(onStep1.narration()).isNotNull();
    }

    @Test
    void 도착이_아닌_이벤트나_풀_밖_키는_서술_대상이_아니다() {
        Verdict noEvent = PanelJudge.judge(pool(), body(null, null,
                List.of(step0("2026-08-01T00:00", List.of())), declared()));
        assertThat(noEvent.narration()).isNull();

        Verdict strangeKey = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-registered", "자유-저작-키"), null,
                List.of(step0("2026-08-01T00:00", List.of())), declared()));
        assertThat(strangeKey.narration()).isNull();
    }

    // ── 분기 갈림길 (#55) ────────────────────────────────────────────────────

    private static final String KEYB0 = "t-branch#0__id=X-1";

    /**
     * 분기 스킬 — 1단계(집계, 항상 1행)에 종료형·열림형 분기가 하나씩. 3단계는
     * 열림형의 대상이라 잠김 출생이고, 2·3단계 바인드는 인자만이라 게이트가 없다면
     * 즉시 카드가 될 수 있는 모양이다.
     */
    private static SkillSpec branched() {
        return new SkillSpec("t-branch", null, null, null, null, null,
                List.of(new SkillSpec.SkillInput("id", true, "조회 키")),
                null,
                List.of(
                        new SkillSpec.SkillStep("1단계 — 집계", null, null, null,
                                "SELECT COUNT(*) AS CNT FROM t0 WHERE id = :id",
                                Map.of("id", new SkillSpec.BindSource("arg", "id", null, null)),
                                List.of(
                                        new SkillSpec.SkillBranch("CNT = 0", "종료하고 없다로 답한다", null),
                                        new SkillSpec.SkillBranch("이탈이 유의미하면", "상세를 본다", 2)),
                                null),
                        new SkillSpec.SkillStep("2단계 — 기본", null, null, null,
                                "SELECT X FROM t1 WHERE id = :id",
                                Map.of("id", new SkillSpec.BindSource("arg", "id", null, null)),
                                null, null),
                        new SkillSpec.SkillStep("3단계 — 상세(조건부)", null, null, null,
                                "SELECT Y FROM t2 WHERE id = :id",
                                Map.of("id", new SkillSpec.BindSource("arg", "id", null, null)),
                                null, null)),
                null, null);
    }

    private static QueryPool branchedPool() {
        return QueryPool.of(List.of(branched()));
    }

    private static PanelBody branchBody(
            PanelEvent event, List<ChatDataSnapshot> snapshots, List<BranchDecision> decisions) {
        return new PanelBody("e1", 1, event, null, null, snapshots,
                List.of(new RunDecl("t-branch", Map.of("id", "X-1"))), null, null, decisions);
    }

    private static ChatDataSnapshot aggregate(String cnt) {
        return new ChatDataSnapshot(KEYB0, "1단계", "2026-08-03T00:00",
                List.of("CNT"), 1, List.of(List.of(cnt)));
    }

    private static ChatDataSnapshot basicStep() {
        return new ChatDataSnapshot("t-branch#1__id=X-1", "2단계", "2026-08-03T00:01",
                List.of("X"), 1, List.of(List.of("x")));
    }

    @Test
    void 조건부_스텝은_잠김_출생이라_판정_사실_없이는_절차_밖이다() {
        // 1·2단계만 밟을 수 있다 — 3단계(열림형 대상)는 판정 사실이 없으면 절차 밖이라
        // 둘이 도착한 순간 종결이다.
        Verdict v = PanelJudge.judge(branchedPool(),
                branchBody(null, List.of(aggregate("5"), basicStep()), null));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.nextStep()).isEqualTo(-1);
        assertThat(run.terminal()).isTrue();
    }

    @Test
    void open_판정_사실이_조건부_스텝을_연다() {
        Verdict v = PanelJudge.judge(branchedPool(), branchBody(null,
                List.of(aggregate("5"), basicStep()),
                List.of(new BranchDecision("t-branch", Map.of("id", "X-1"), 0, "open", 1, null))));

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.nextStep()).isEqualTo(2);
        assertThat(run.terminal()).isFalse();
    }

    @Test
    void 분기_있는_스텝의_도착이_판정_질문을_선언한다() {
        PanelEvent event = new PanelEvent("snapshot-registered", KEYB0);

        Verdict fresh = PanelJudge.judge(branchedPool(),
                branchBody(event, List.of(aggregate("0")), null));
        assertThat(fresh.branchQuestion()).isNotNull();
        assertThat(fresh.branchQuestion().skill()).isEqualTo("t-branch");
        assertThat(fresh.branchQuestion().query().step()).isZero();
        assertThat(fresh.branchQuestion().data().rows()).containsExactly(List.of("0"));

        // 이미 그 스텝의 판정 사실이 있으면(재전송) 다시 묻지 않는다.
        Verdict judged = PanelJudge.judge(branchedPool(), branchBody(event,
                List.of(aggregate("0")),
                List.of(new BranchDecision("t-branch", Map.of("id", "X-1"), 0, "open", 1, null))));
        assertThat(judged.branchQuestion()).isNull();

        // 0행 도착은 기존 결정론 종결 소관 — 분기 판정 대상이 아니다.
        Verdict empty = PanelJudge.judge(branchedPool(), branchBody(event,
                List.of(new ChatDataSnapshot(KEYB0, "1단계", "2026-08-03T00:00",
                        List.of("CNT"), 0, List.of())),
                null));
        assertThat(empty.branchQuestion()).isNull();

        // 이벤트가 없으면(재동기화 왕복) 묻지 않는다.
        Verdict noEvent = PanelJudge.judge(branchedPool(),
                branchBody(null, List.of(aggregate("0")), null));
        assertThat(noEvent.branchQuestion()).isNull();
    }

    @Test
    void stop_판정_재판정은_절차를_끝내고_분기_지시가_실린_서술을_낸다() {
        BranchDecision stop = new BranchDecision(
                "t-branch", Map.of("id", "X-1"), 0, "stop", 0, "CNT가 0건입니다.");
        Verdict v = PanelJudge.judge(branchedPool(),
                branchBody(new PanelEvent("snapshot-registered", KEYB0),
                        List.of(aggregate("0")), null),
                stop);

        assertThat(v.branchQuestion()).isNull(); // 재판정 경로는 다시 묻지 않는다.
        RunProgress run = v.runsProgress().get(0);
        assertThat(run.terminal()).isTrue();
        assertThat(v.terminalRuns()).containsExactly(run.label());
        assertThat(v.narration()).isNotNull();
        assertThat(v.narration().branchNote())
                .contains("CNT = 0").contains("CNT가 0건입니다.").contains("종료하고 없다로 답한다");
    }

    @Test
    void 저장된_stop_사실이_돌아오면_종결은_유지되고_서술은_다시_나지_않는다() {
        // FE 가 카드에 저장해 되보낸 사실 — 이벤트 없는 재동기화에서 절차는 닫힌 채다.
        Verdict v = PanelJudge.judge(branchedPool(), branchBody(null,
                List.of(aggregate("0")),
                List.of(new BranchDecision("t-branch", Map.of("id", "X-1"), 0, "stop", 0, null))));

        assertThat(v.runsProgress().get(0).terminal()).isTrue();
        assertThat(v.narration()).isNull();
    }
}

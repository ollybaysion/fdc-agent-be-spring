package fdc.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.PanelJudge.PanelBody;
import fdc.agent.chat.PanelJudge.Verdict;
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
 * panel-judge 판정(#38) — 무상태 순수함수: 선언 ∪ 도착에서 카드·진행·pick·서술
 * 전이가 결정론으로 나온다.
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

    /** 선택 인자를 바인드로 쓰는 스킬 — 선언 경로로 완성할 수 없다(T2). */
    private static SkillSpec optionalBind() {
        return new SkillSpec("t-opt", null, null, null, null, null,
                List.of(new SkillSpec.SkillInput("id", true, "조회 키"),
                        new SkillSpec.SkillInput("opt", false, "선택")),
                null,
                List.of(new SkillSpec.SkillStep("1단계", null, null, null,
                        "SELECT X FROM q WHERE o = :o",
                        Map.of("o", new SkillSpec.BindSource("arg", "opt", null, null)),
                        null, null)),
                null, null);
    }

    private static QueryPool pool() {
        return QueryPool.of(List.of(twoStep(), optionalBind()));
    }

    private static PanelBody body(
            PanelEvent event, List<SnapshotIndexEntry> index, List<ChatDataSnapshot> snapshots,
            List<RunDecl> runs, Map<String, Map<String, String>> picks) {
        return new PanelBody("e1", 1, event, null, index, snapshots, runs, null, null, picks);
    }

    private static List<RunDecl> declared() {
        return List.of(new RunDecl("t-two-step", Map.of("id", "X-1")));
    }

    private static ChatDataSnapshot step0(String capturedAt, List<List<String>> rows) {
        return new ChatDataSnapshot(KEY0, "1단계", capturedAt, List.of("A", "B"),
                rows.size(), rows);
    }

    @Test
    void 선언만_있고_스냅샷이_없어도_첫_단계_카드가_나온다() {
        // T3: run 은 도착에서만 유도되지 않는다 — 선언이 절차의 시작을 연다.
        Verdict v = PanelJudge.judge(pool(), body(null, null, null, declared(), null));

        assertThat(v.openRequests()).hasSize(1);
        assertThat(v.openRequests().get(0).queryKey()).isEqualTo(KEY0);
        assertThat(v.openRequests().get(0).sql()).contains("id = 'X-1'").doesNotContain(":id");
        // 카드의 소속(run) — FE 가 설비→분석 계층에 앉히는 근거. 선언 원문이 그대로 돌아온다.
        assertThat(v.openRequests().get(0).run()).isEqualTo(declared().get(0));
        assertThat(v.runsProgress()).hasSize(1);
        assertThat(v.runsProgress().get(0).nextStep()).isEqualTo(0);
        assertThat(v.runsProgress().get(0).terminal()).isFalse();
    }

    @Test
    void 앞_단계가_도착하면_다음_단계_카드가_나온다() {
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(step0("2026-08-01T00:00", List.of(List.of("a1", "b1")))),
                declared(), null));

        assertThat(v.openRequests()).hasSize(1);
        assertThat(v.openRequests().get(0).queryKey()).isEqualTo(KEY1);
        assertThat(v.openRequests().get(0).sql()).contains("a = 'a1'");
    }

    @Test
    void 도착_요약만_있고_rows_가_없으면_needsRows_로_요구한다() {
        // T16: 도착 여부는 index 가 진실이지만 바인드 값 추출엔 rows 가 필요하다.
        Verdict v = PanelJudge.judge(pool(), body(null,
                List.of(new SnapshotIndexEntry(KEY0, "1단계", "2026-08-01T00:00",
                        List.of("A", "B"), 1, "h1", true)),
                null, declared(), null));

        assertThat(v.needsRows()).containsExactly(KEY0);
        assertThat(v.openRequests()).isEmpty();
    }

    @Test
    void 체크_해제된_스냅샷은_판정_밖이다() {
        // T4: 판정 집합은 패널 전체가 아니라 사용자가 켜 둔 것이다.
        Verdict v = PanelJudge.judge(pool(), body(null,
                List.of(new SnapshotIndexEntry(KEY0, "1단계", "2026-08-01T00:00",
                        List.of("A", "B"), 1, "h1", false)),
                null, declared(), null));

        assertThat(v.needsRows()).isEmpty();
        assertThat(v.openRequests()).hasSize(1); // 1단계가 도착하지 않은 것으로 판정 — 첫 카드.
        assertThat(v.openRequests().get(0).queryKey()).isEqualTo(KEY0);
    }

    @Test
    void 같은_키_다중_도착은_capturedAt_최신_한_건만_본다() {
        // T9: 배열 순서 last-wins 가 아니라 시각 규칙 — 순수함수가 입력 순서에 흔들리지 않는다.
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(step0("2026-08-01T02:00", List.of(List.of("a2", "b2"))),
                        step0("2026-08-01T01:00", List.of(List.of("a1", "b1")))),
                declared(), null));

        assertThat(v.openRequests()).hasSize(1);
        assertThat(v.openRequests().get(0).sql()).contains("a = 'a2'");
    }

    @Test
    void 영행_확인이면_종결이고_하위_카드가_없다() {
        // T5: 0행도 도착이고 종결이다 — 없다는 사실로 절차가 끝난다.
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(step0("2026-08-01T00:00", List.of())),
                declared(), null));

        assertThat(v.openRequests()).isEmpty();
        RunProgress run = v.runsProgress().get(0);
        assertThat(run.terminal()).isTrue();
        assertThat(run.emptyAtStep()).isEqualTo(0);
        assertThat(v.terminalRuns()).containsExactly(run.label());
    }

    @Test
    void 미선언_절차는_진행만_보고하고_카드는_만들지_않는다() {
        // T1: queryKey 는 손실 인코딩 — 키에서 복원한 args 로 SQL 을 만들지 않는다.
        Verdict v = PanelJudge.judge(pool(), body(null, null,
                List.of(step0("2026-08-01T00:00", List.of(List.of("a1", "b1")))),
                null, null));

        assertThat(v.openRequests()).isEmpty();
        RunProgress run = v.runsProgress().get(0);
        assertThat(run.holds()).isNotNull();
        assertThat(run.holds().get(0).reason()).contains("runs[]");
    }

    @Test
    void 여러_값이면_pick_후보를_선언하고_pick_이_오면_카드가_나온다() {
        // T8: 갈림길은 사람이 고른다 — 후보 밖 값은 받지 않는다.
        List<ChatDataSnapshot> arrived = List.of(step0("2026-08-01T00:00",
                List.of(List.of("a1", "b1"), List.of("a2", "b2"))));

        Verdict withoutPick = PanelJudge.judge(pool(), body(null, null, arrived, declared(), null));
        assertThat(withoutPick.openRequests()).isEmpty();
        RunProgress run = withoutPick.runsProgress().get(0);
        assertThat(run.needsPick()).hasSize(1);
        assertThat(run.needsPick().get(0).queryId()).isEqualTo("t-two-step#1");
        assertThat(run.needsPick().get(0).column()).isEqualTo("A");
        assertThat(run.needsPick().get(0).candidates()).containsExactly("a1", "a2");

        Verdict withPick = PanelJudge.judge(pool(), body(null, null, arrived, declared(),
                Map.of("t-two-step#1", Map.of("A", "a2"))));
        assertThat(withPick.openRequests()).hasSize(1);
        assertThat(withPick.openRequests().get(0).sql()).contains("a = 'a2'");
    }

    @Test
    void 선택_인자_바인드_스텝은_카드_대신_사유를_보고한다() {
        // T2: 선언 경로에서 영구 미완성일 스텝 — 조용한 멈춤 대신 보류 보고.
        Verdict v = PanelJudge.judge(pool(), body(null, null, null,
                List.of(new RunDecl("t-opt", Map.of("id", "1"))), null));

        assertThat(v.openRequests()).isEmpty();
        RunProgress run = v.runsProgress().get(0);
        assertThat(run.holds()).isNotNull();
        assertThat(run.holds().get(0).reason()).contains("선택 인자");
    }

    @Test
    void 등재되지_않은_스킬_선언은_사유를_보고한다() {
        Verdict v = PanelJudge.judge(pool(), body(null, null, null,
                List.of(new RunDecl("no-such-skill", Map.of())), null));

        assertThat(v.openRequests()).isEmpty();
        assertThat(v.runsProgress().get(0).holds().get(0).reason()).contains("등재되지 않은");
    }

    @Test
    void 이벤트가_절차를_종결로_완성시키면_서술이_난다() {
        // T7: "새로 terminal" 을 상태 없이 인과로 판정 — 이 이벤트를 빼면 종결이 아니어야 한다.
        Verdict v = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-registered", KEY0), null,
                List.of(step0("2026-08-01T00:00", List.of())),
                declared(), null));

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
                new PanelEvent("snapshot-updated", KEY0), null, arrived, declared(), null));
        assertThat(onStep0.narration()).isNull();

        Verdict onStep1 = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-registered", KEY1), null, arrived, declared(), null));
        assertThat(onStep1.narration()).isNotNull();
    }

    @Test
    void 도착이_아닌_이벤트나_풀_밖_키는_서술_대상이_아니다() {
        Verdict noEvent = PanelJudge.judge(pool(), body(null, null,
                List.of(step0("2026-08-01T00:00", List.of())), declared(), null));
        assertThat(noEvent.narration()).isNull();

        Verdict strangeKey = PanelJudge.judge(pool(), body(
                new PanelEvent("snapshot-registered", "자유-저작-키"), null,
                List.of(step0("2026-08-01T00:00", List.of())), declared(), null));
        assertThat(strangeKey.narration()).isNull();
    }
}

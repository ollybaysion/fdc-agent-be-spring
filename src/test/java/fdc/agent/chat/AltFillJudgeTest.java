package fdc.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.AltFillJudge.AltFill;
import fdc.agent.chat.PanelJudge.PanelBody;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.RunDecl;
import fdc.agent.contract.RunProgress;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 채움 폭포의 3차 — 결정론이 이름으로 못 이은 사실을 LLM 이 잇는다.
 *
 * <p>규율 둘이 이 층의 전부다: <b>부를 조건이 좁고</b>(못 채운 것과 못 쓴 표가 동시에
 * 있을 때만), <b>값은 표에 실제로 있어야 받는다</b>. 여기서 나온 값은 확인 없이 다음
 * 조회의 바인드로 들어가므로 창작을 막는 자리가 이 검증뿐이다.
 */
class AltFillJudgeTest {

    private static final String BASE = "t-two-step#base_row__id=X-1";

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

    private static final QueryPool POOL = QueryPool.of(List.of(twoStep()));

    /** 이름이 우리 spec 과 다른, 사용자가 그냥 붙여넣은 표. */
    private static ChatDataSnapshot pasted() {
        return new ChatDataSnapshot("사내리포트", "설비 현황", "2026-08-01T00:00",
                List.of("장비번호", "상세코드"), 1, List.of(List.of("X-1", "c9")));
    }

    private static ChatDataSnapshot baseRow() {
        return new ChatDataSnapshot(BASE, "기준", "2026-08-01T00:00",
                List.of("A", "B"), 1, List.of(List.of("a1", "b1")));
    }

    private static PanelBody body(List<ChatDataSnapshot> snapshots) {
        return new PanelBody("e1", 1, null, null, null, snapshots,
                List.of(new RunDecl("t-two-step", Map.of("id", "X-1"))), null, null);
    }

    /** 정해진 문장을 돌려주며 호출 횟수를 세는 fake. */
    private static class Scripted implements LlmClient {
        private final String answer;
        int calls;

        Scripted(String answer) {
            this.answer = answer;
        }

        @Override
        public LlmTurn next(List<fdc.agent.llm.LlmTypes.LlmMessage> messages,
                List<fdc.agent.llm.LlmTypes.LlmToolSpec> tools) {
            calls++;
            return new LlmTurn.Final(answer);
        }
    }

    private static List<RunProgress> progressOf(List<ChatDataSnapshot> snapshots) {
        return PanelJudge.judge(POOL, body(snapshots)).runsProgress();
    }

    @Test
    void 못_채운_것이_다른_표에_있으면_잇는다() {
        Scripted llm = new Scripted(
                "{\"fills\":[{\"need\":\"detail\",\"source\":\"사내리포트\","
                        + "\"column\":\"상세코드\",\"value\":\"c9\"}]}");
        List<ChatDataSnapshot> snapshots = List.of(baseRow(), pasted());

        Map<String, List<AltFill>> fills =
                AltFillJudge.ask(llm, POOL, body(snapshots), progressOf(snapshots));

        assertThat(llm.calls).isEqualTo(1);
        assertThat(fills).containsOnlyKeys("t-two-step id=X-1");
        assertThat(fills.get("t-two-step id=X-1"))
                .containsExactly(new AltFill("detail", "c9", "사내리포트"));
    }

    @Test
    void 이어진_사실은_판정에_얹혀_절차를_끝낸다() {
        List<ChatDataSnapshot> snapshots = List.of(baseRow(), pasted());
        Map<String, List<AltFill>> fills = Map.of(
                "t-two-step id=X-1", List.of(new AltFill("detail", "c9", "사내리포트")));

        PanelJudge.Verdict v = PanelJudge.judge(POOL, body(snapshots), fills);

        RunProgress run = v.runsProgress().get(0);
        assertThat(run.outcome()).isEqualTo("SUFFICIENT");
        assertThat(run.terminal()).isTrue();
        RunProgress.Need detail = run.needs().get(1);
        assertThat(detail.state()).isEqualTo("FILLED");
        assertThat(detail.source()).isEqualTo("사내리포트"); // 어디서 온 값인지 남는다.
    }

    @Test
    void 표에_없는_값은_버린다() {
        // 이 값은 확인 없이 다음 조회의 바인드로 들어간다 — 창작을 막는 자리가 여기뿐이다.
        Scripted llm = new Scripted(
                "{\"fills\":[{\"need\":\"detail\",\"source\":\"사내리포트\","
                        + "\"column\":\"상세코드\",\"value\":\"지어낸값\"}]}");
        List<ChatDataSnapshot> snapshots = List.of(baseRow(), pasted());

        assertThat(AltFillJudge.ask(llm, POOL, body(snapshots), progressOf(snapshots))).isEmpty();
    }

    @Test
    void 묻지_않은_항목은_받지_않는다() {
        Scripted llm = new Scripted(
                "{\"fills\":[{\"need\":\"없는_항목\",\"source\":\"사내리포트\",\"value\":\"c9\"}]}");
        List<ChatDataSnapshot> snapshots = List.of(baseRow(), pasted());

        assertThat(AltFillJudge.ask(llm, POOL, body(snapshots), progressOf(snapshots))).isEmpty();
    }

    @Test
    void 결정론이_못_쓴_표가_없으면_아예_묻지_않는다() {
        // 판정 왕복마다 LLM 이 도는 설계가 아니다.
        Scripted llm = new Scripted("{\"fills\":[]}");
        List<ChatDataSnapshot> snapshots = List.of(baseRow());

        assertThat(AltFillJudge.ask(llm, POOL, body(snapshots), progressOf(snapshots))).isEmpty();
        assertThat(llm.calls).isZero();
    }

    @Test
    void 못_채운_것이_없으면_묻지_않는다() {
        Scripted llm = new Scripted("{\"fills\":[]}");
        // 두 need 가 지목한 조달이 모두 도착 — 남은 것이 없으니 물어볼 것도 없다.
        List<ChatDataSnapshot> full = List.of(
                baseRow(),
                new ChatDataSnapshot("t-two-step#detail_row__id=X-1", "상세",
                        "2026-08-01T00:10", List.of("C"), 1, List.of(List.of("c1"))),
                pasted());

        assertThat(AltFillJudge.ask(llm, POOL, body(full), progressOf(full))).isEmpty();
        assertThat(llm.calls).isZero();
    }

    @Test
    void LLM_이_실패해도_판정은_그대로다() {
        LlmClient broken = (messages, tools) -> {
            throw new IllegalStateException("게이트웨이 없음");
        };
        List<ChatDataSnapshot> snapshots = List.of(baseRow(), pasted());

        assertThat(AltFillJudge.ask(broken, POOL, body(snapshots), progressOf(snapshots)))
                .isEmpty();
    }

    @Test
    void JSON_이_아닌_답은_조용히_버린다() {
        Scripted llm = new Scripted("잘 모르겠습니다.");
        List<ChatDataSnapshot> snapshots = List.of(baseRow(), pasted());

        assertThat(AltFillJudge.ask(llm, POOL, body(snapshots), progressOf(snapshots))).isEmpty();
    }

    @Test
    void 물어보는_문장에_못_채운_것과_표가_함께_실린다() {
        List<String> asked = new ArrayList<>();
        LlmClient capture = (messages, tools) -> {
            messages.forEach(m -> asked.add(m.content()));
            return new LlmTurn.Final("{\"fills\":[]}");
        };
        List<ChatDataSnapshot> snapshots = List.of(baseRow(), pasted());

        AltFillJudge.ask(capture, POOL, body(snapshots), progressOf(snapshots));

        String prompt = String.join("\n", asked);
        assertThat(prompt)
                .contains("detail: 상세")
                .contains("원래 자리: detail_row.C") // 어디에 있어야 할 값인지
                .contains("사내리포트")
                .contains("장비번호, 상세코드")
                .contains("X-1 | c9");
    }
}

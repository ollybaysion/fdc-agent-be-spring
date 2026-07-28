package fdc.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 진행은 저장하지 않는다 — 도착한 스냅샷의 키에서 유도한다. 키가 곧 상태라는 게
 * 이 설계의 전부라, 여기서 깨지면 이어가기가 통째로 깨진다.
 */
class QueryProgressTest {

    private static final QueryPool POOL = QueryPool.of(SkillRegistry.bundledSpecs());

    private static ChatDataSnapshot snap(String key, List<String> cols, List<List<String>> rows) {
        return new ChatDataSnapshot(key, "1단계", "2026-07-28T00:00", cols,
                rows != null ? rows.size() : null, rows);
    }

    private static ChatDataSnapshot step0() {
        return snap("fdc-explain-sensor#0__snsr_id=S-0004",
                List.of("SNSR_ID", "EQP_ID"), List.of(List.of("S-0004", "CVD-01")));
    }

    @Test
    void 도착한_스냅샷에서_다음_한_걸음이_나온다() {
        QueryProgress progress = QueryProgress.of(POOL, List.of(step0()));

        assertThat(progress.runs()).hasSize(1);
        QueryProgress.Run run = progress.runs().get(0);
        assertThat(run.skill()).isEqualTo("fdc-explain-sensor");
        assertThat(run.args()).containsEntry("snsr_id", "S-0004");
        assertThat(run.nextStep()).isEqualTo(1);

        // 프롬프트 줄은 그대로 베껴 부를 수 있는 모양이어야 한다.
        assertThat(progress.promptSection())
                .contains(ChatPrompt.SECTION_PROGRESS)
                .contains("3단계 중 1단계 도착")
                .contains("queryId=\"fdc-explain-sensor#1\", args={\"snsr_id\":\"S-0004\"}");
    }

    @Test
    void 인자가_다르면_다른_절차로_갈린다() {
        QueryProgress progress = QueryProgress.of(POOL, List.of(
                step0(),
                snap("fdc-explain-sensor#0__snsr_id=S-0007",
                        List.of("SNSR_ID", "EQP_ID"), List.of(List.of("S-0007", "ETCH-01")))));

        assertThat(progress.runsOf("fdc-explain-sensor")).hasSize(2);
    }

    @Test
    void 조회_결과_0행이면_절차가_거기서_끝난다() {
        // 0행은 "아직 안 왔다"가 아니라 "없다"이다 — 다음 걸음을 적지 않는다.
        QueryProgress progress = QueryProgress.of(POOL, List.of(
                snap("fdc-explain-sensor#0__snsr_id=S-9999", List.of("SNSR_ID"), List.of())));

        assertThat(progress.runs().get(0).emptyAt()).isNotNull();
        assertThat(progress.promptSection())
                .contains("0행 — 데이터가 없음이 확인됐다")
                .doesNotContain("다음 =");
    }

    @Test
    void 내용이_안_온_항목은_도착으로_치지_않는다() {
        // 키만 있고 내용이 없는 항목까지 도착으로 보면 다시 요청할 길이 막힌다.
        ChatDataSnapshot catalogOnly = new ChatDataSnapshot(
                "fdc-explain-sensor#0__snsr_id=S-0004", "1단계", "2026-07-28T00:00",
                List.of("SNSR_ID"), 5, null);
        QueryProgress progress = QueryProgress.of(POOL, List.of(catalogOnly));

        assertThat(progress.runs()).isEmpty();
        assertThat(progress.arrived("fdc-explain-sensor#0__snsr_id=S-0004")).isNull();
    }

    @Test
    void 풀_밖의_옛_키는_진행으로_읽지_않는다() {
        // 사용자 브라우저에 남은 자유 저작 스냅샷 — 적재·억제는 받되 진행은 아니다.
        QueryProgress progress = QueryProgress.of(POOL, List.of(
                snap("sensor_list", List.of("A"), List.of(List.of("1")))));

        assertThat(progress.runs()).isEmpty();
        assertThat(progress.arrived("sensor_list")).isNotNull();
    }

    @Test
    void 모든_단계가_도착하면_더_요청할_것이_없다고_적는다() {
        QueryProgress progress = QueryProgress.of(POOL, List.of(
                step0(),
                snap("fdc-explain-sensor#1__snsr_id=S-0004", List.of("EQP_ID"),
                        List.of(List.of("CVD-01"))),
                snap("fdc-explain-sensor#2__snsr_id=S-0004", List.of("D"),
                        List.of(List.of("2026-05-11")))));

        assertThat(progress.runs().get(0).nextStep()).isEqualTo(-1);
        assertThat(progress.promptSection()).contains("3단계 모두 도착").doesNotContain("다음 =");
    }

    @Test
    void 컬럼_값은_대소문자를_가리지_않고_중복을_접는다() {
        ChatDataSnapshot s = snap("k", List.of("eqp_id"),
                Arrays.asList(List.of("CVD-01"), List.of("CVD-01"), Arrays.asList((String) null)));

        assertThat(QueryProgress.valuesOf(s, "EQP_ID")).containsExactly("CVD-01");
        assertThat(QueryProgress.valuesOf(s, "NOPE")).isEmpty();
    }

    @Test
    void run_이름표는_단계가_달라도_같다() {
        Map<String, String> args = Map.of("snsr_id", "S-0004");
        List<String> names = List.of("snsr_id");
        assertThat(QueryKey.of("fdc-explain-sensor", 0, args, names))
                .isEqualTo("fdc-explain-sensor#0__snsr_id=S-0004");
        assertThat(QueryKey.of("fdc-explain-sensor", 1, args, names))
                .isEqualTo("fdc-explain-sensor#1__snsr_id=S-0004");
    }

    @Test
    void 구분자가_섞인_값도_키를_깨뜨리지_않는다() {
        String key = QueryKey.of("s", 0, Map.of("a", "x=1&y#2"), List.of("a"));
        QueryKey.Parsed parsed = QueryKey.parse(key);
        assertThat(parsed).isNotNull();
        assertThat(parsed.step()).isZero();
        assertThat(QueryKey.parseArgs(parsed.argsPart())).containsEntry("a", "x_1_y_2");
    }

    @Test
    void 풀_형식이_아닌_키는_파싱되지_않는다() {
        assertThat(QueryKey.parse("sensor_list")).isNull();
        assertThat(QueryKey.parse(null)).isNull();
    }
}

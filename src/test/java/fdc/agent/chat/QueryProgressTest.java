package fdc.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.skills.NeedsResolver;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 진행은 저장하지 않는다 — 도착한 스냅샷의 키에서 유도한다. 키가 곧 상태라는 게
 * 이 설계의 전부라, 여기서 깨지면 이어가기가 통째로 깨진다.
 *
 * <p>v3 에서 유도의 단위가 단계에서 need 로 바뀌었다: "몇 단계 왔나"가 아니라
 * "무엇을 알아냈고 무엇이 모자란가"다.
 */
class QueryProgressTest {

    private static final QueryPool POOL = QueryPool.of(SkillRegistry.bundledSpecs());

    private static ChatDataSnapshot snap(String key, List<String> cols, List<List<String>> rows) {
        return new ChatDataSnapshot(key, "조달", "2026-07-28T00:00", cols,
                rows != null ? rows.size() : null, rows);
    }

    /** 센서 기본 행 — needs 넷이 여기서 찬다. */
    private static ChatDataSnapshot sensorRow(String useYn) {
        return snap("fdc-explain-sensor#sensor_row__snsr_id=S-0004",
                List.of("SNSR_ID", "EQP_ID", "SNSR_TYPE_CD", "UNIT_CD", "USE_YN"),
                List.of(List.of("S-0004", "CVD-01", "FLOW", "SCCM", useYn)));
    }

    private static ChatDataSnapshot equipmentRow() {
        return snap("fdc-explain-sensor#equipment_row__snsr_id=S-0004",
                List.of("EQP_ID", "EQP_NAME", "MODEL_CD", "VENDOR", "USE_YN"),
                List.of(List.of("CVD-01", "증착기 1호", "CV-800", "AMAT", "N")));
    }

    private static ChatDataSnapshot eventRows() {
        return snap("fdc-explain-sensor#setup_event_rows__snsr_id=S-0004",
                List.of("D", "EVT_TYPE_CD", "EVT_LABEL"),
                List.of(List.of("2026-05-11", "PM", "라인 점검")));
    }

    private static NeedsResolver.NeedStatus need(QueryProgress.Run run, String id) {
        return run.resolution().needs().stream()
                .filter(n -> n.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 도착한_스냅샷에서_다음_한_걸음이_나온다() {
        QueryProgress progress = QueryProgress.of(POOL, List.of(sensorRow("Y")));

        assertThat(progress.runs()).hasSize(1);
        QueryProgress.Run run = progress.runs().get(0);
        assertThat(run.skill()).isEqualTo("fdc-explain-sensor");
        assertThat(run.args()).containsEntry("snsr_id", "S-0004");
        assertThat(run.resolution().outcome()).isEqualTo(NeedsResolver.Outcome.PROCURABLE);
        assertThat(run.resolution().wanted()).containsExactly("equipment_row", "setup_event_rows");

        // 프롬프트 줄은 그대로 베껴 부를 수 있는 모양이어야 한다.
        assertThat(progress.promptSection())
                .contains(ChatPrompt.SECTION_PROGRESS)
                .contains("알아낸 것: 무엇을 재는 센서인지")
                .contains("아직 모르는 것: 그 설비의 이름과 모델")
                .contains("queryId=\"fdc-explain-sensor#equipment_row\","
                        + " args={\"snsr_id\":\"S-0004\"}");
    }

    @Test
    void 게이트는_앞_need_의_값으로_열리고_닫힌다() {
        // 센서가 살아 있으면 "설비가 미사용인가"는 이번 질문에서 알 필요가 없다.
        QueryProgress.Run alive = QueryProgress.of(POOL, List.of(sensorRow("Y"))).runs().get(0);
        assertThat(need(alive, "equipment_active").state())
                .isEqualTo(NeedsResolver.State.INACTIVE);

        // 비활성이면 그때 열린다 — 갈림형 분기가 데이터로 결정된다.
        QueryProgress.Run dead = QueryProgress.of(POOL, List.of(sensorRow("N"))).runs().get(0);
        assertThat(need(dead, "equipment_active").state())
                .isEqualTo(NeedsResolver.State.UNFILLED);
    }

    @Test
    void 인자가_다르면_다른_절차로_갈린다() {
        QueryProgress progress = QueryProgress.of(POOL, List.of(
                sensorRow("Y"),
                snap("fdc-explain-sensor#sensor_row__snsr_id=S-0007",
                        List.of("SNSR_ID", "EQP_ID", "SNSR_TYPE_CD", "UNIT_CD", "USE_YN"),
                        List.of(List.of("S-0007", "ETCH-01", "TEMP", "C", "Y")))));

        assertThat(progress.runsOf("fdc-explain-sensor")).hasSize(2);
    }

    @Test
    void 조회_결과_0행이면_더_조달할_수단이_없다() {
        // 센서가 없으면 그 값으로 이어가는 조달도 영영 못 돈다 — 조달가능이 아니라 답불가다.
        QueryProgress progress = QueryProgress.of(POOL, List.of(
                snap("fdc-explain-sensor#sensor_row__snsr_id=S-9999",
                        List.of("SNSR_ID"), List.of())));

        QueryProgress.Run run = progress.runs().get(0);
        assertThat(run.resolution().outcome()).isEqualTo(NeedsResolver.Outcome.UNANSWERABLE);
        assertThat(run.resolution().terminal()).isTrue();
        assertThat(progress.promptSection())
                .contains("더 조달할 수단이 없다")
                .doesNotContain("다음 =");
    }

    @Test
    void 내용이_안_온_항목은_도착으로_치지_않는다() {
        // 키만 있고 내용이 없는 항목까지 도착으로 보면 다시 요청할 길이 막힌다.
        ChatDataSnapshot catalogOnly = new ChatDataSnapshot(
                "fdc-explain-sensor#sensor_row__snsr_id=S-0004", "조달", "2026-07-28T00:00",
                List.of("SNSR_ID"), 5, null);
        QueryProgress progress = QueryProgress.of(POOL, List.of(catalogOnly));

        assertThat(progress.runs()).isEmpty();
        assertThat(progress.arrived("fdc-explain-sensor#sensor_row__snsr_id=S-0004")).isNull();
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
    void 활성_need_가_다_차면_더_요청할_것이_없다고_적는다() {
        QueryProgress progress = QueryProgress.of(POOL,
                List.of(sensorRow("Y"), equipmentRow(), eventRows()));

        QueryProgress.Run run = progress.runs().get(0);
        assertThat(run.resolution().outcome()).isEqualTo(NeedsResolver.Outcome.SUFFICIENT);
        assertThat(progress.promptSection())
                .contains("알아야 할 것을 모두 확인했다")
                .doesNotContain("다음 =");
    }

    @Test
    void 컬럼_값은_대소문자를_가리지_않고_중복을_접는다() {
        ChatDataSnapshot s = snap("k", List.of("eqp_id"),
                Arrays.asList(List.of("CVD-01"), List.of("CVD-01"), Arrays.asList((String) null)));

        assertThat(QueryProgress.valuesOf(s, "EQP_ID")).containsExactly("CVD-01");
        assertThat(QueryProgress.valuesOf(s, "NOPE")).isEmpty();
    }

    @Test
    void run_이름표는_조달이_달라도_같다() {
        Map<String, String> args = Map.of("snsr_id", "S-0004");
        List<String> names = List.of("snsr_id");
        assertThat(QueryKey.of("fdc-explain-sensor", "sensor_row", args, names))
                .isEqualTo("fdc-explain-sensor#sensor_row__snsr_id=S-0004");
        assertThat(QueryKey.of("fdc-explain-sensor", "equipment_row", args, names))
                .isEqualTo("fdc-explain-sensor#equipment_row__snsr_id=S-0004");
    }

    @Test
    void 구분자가_섞인_값도_키를_깨뜨리지_않는다() {
        String key = QueryKey.of("s", "one", Map.of("a", "x=1&y#2"), List.of("a"));
        QueryKey.Parsed parsed = QueryKey.parse(key);
        assertThat(parsed).isNotNull();
        assertThat(parsed.query()).isEqualTo("one");
        assertThat(QueryKey.parseArgs(parsed.argsPart())).containsEntry("a", "x_1_y_2");
    }

    @Test
    void 풀_형식이_아닌_키는_파싱되지_않는다() {
        assertThat(QueryKey.parse("sensor_list")).isNull();
        assertThat(QueryKey.parse(null)).isNull();
    }
}

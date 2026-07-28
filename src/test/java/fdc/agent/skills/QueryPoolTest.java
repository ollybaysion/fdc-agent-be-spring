package fdc.agent.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 풀은 스킬 spec 에서 자동으로 나오고, 모델의 표기 흔들림을 흡수한다. */
class QueryPoolTest {

    private static final QueryPool POOL = QueryPool.of(SkillRegistry.bundledSpecs());

    @Test
    void spec_의_모든_스텝이_조회가_된다() {
        assertThat(POOL.ids()).containsExactly(
                "fdc-explain-sensor#0", "fdc-explain-sensor#1", "fdc-explain-sensor#2",
                "fdc-trace-reading#0", "fdc-trace-reading#1", "fdc-trace-reading#2");
    }

    @Test
    void 스킬_툴_이름_표기로_불러도_찾는다() {
        // 스킬 툴 이름은 fdc_explain_sensor, spec 이름은 fdc-explain-sensor — 모델이 섞는다.
        assertThat(POOL.byId("fdc_explain_sensor#1")).isNotNull()
                .extracting(QueryPool.Query::queryId).isEqualTo("fdc-explain-sensor#1");
    }

    @Test
    void 단계를_안_적으면_첫_단계로_본다() {
        assertThat(POOL.byId("fdc-trace-reading")).isNotNull()
                .extracting(QueryPool.Query::step).isEqualTo(0);
    }

    @Test
    void 풀에_없는_이름은_찾지_못한다() {
        assertThat(POOL.byId("fdc-explain-sensor#9")).isNull();
        assertThat(POOL.byId("sensor_list")).isNull();
    }

    @Test
    void run_이름표에_쓸_인자는_스킬의_필수_인자_전량이다() {
        // 그 단계가 실제로 쓰는 바인드가 아니라 — 그래야 모든 단계가 같은 이름표를 단다.
        QueryPool.Query second = POOL.byId("fdc-explain-sensor#1");
        assertThat(second.requiredArgs()).containsExactly("snsr_id");
        assertThat(second.dependsOnStep()).isTrue();
        assertThat(POOL.byId("fdc-trace-reading#1").requiredArgs())
                .containsExactly("end", "equipment", "param_index", "start");
    }

    @Test
    void 카탈로그는_인자와_앞_단계_의존을_적는다() {
        String catalog = POOL.catalogText();
        assertThat(catalog).contains("fdc-explain-sensor#0 —").contains("(인자: snsr_id)");
        assertThat(catalog).contains("fdc-explain-sensor#1").contains("※ 앞 단계 결과 필요");
    }

    @Test
    void 스킬이_없으면_풀도_비어_있다() {
        assertThat(QueryPool.of(java.util.List.of()).isEmpty()).isTrue();
    }
}

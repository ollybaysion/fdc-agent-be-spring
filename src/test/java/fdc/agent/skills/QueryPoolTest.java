package fdc.agent.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 풀은 스킬 spec 에서 자동으로 나오고, 모델의 표기 흔들림을 흡수한다. */
class QueryPoolTest {

    private static final QueryPool POOL = QueryPool.of(SkillRegistry.bundledSpecs());

    @Test
    void spec_의_모든_조달이_풀에_오른다() {
        assertThat(POOL.ids()).containsExactly(
                "fdc-explain-sensor#sensor_row",
                "fdc-explain-sensor#equipment_row",
                "fdc-explain-sensor#setup_event_rows",
                "fdc-trace-reading#reading_stats",
                "fdc-trace-reading#equipment_row",
                "fdc-trace-reading#setup_event_rows");
    }

    @Test
    void 스킬_툴_이름_표기로_불러도_찾는다() {
        // 스킬 툴 이름은 fdc_explain_sensor, spec 이름은 fdc-explain-sensor — 모델이 섞는다.
        assertThat(POOL.byId("fdc_explain_sensor#equipment_row")).isNotNull()
                .extracting(QueryPool.Query::queryId).isEqualTo("fdc-explain-sensor#equipment_row");
    }

    @Test
    void 조달을_안_적으면_후보가_하나일_때만_고른다() {
        // v2 는 "첫 단계"로 폈지만 카탈로그에는 첫째가 없다 — 모호하면 고르지 않는다.
        assertThat(POOL.byId("fdc-trace-reading")).isNull();
    }

    @Test
    void 풀에_없는_이름은_찾지_못한다() {
        assertThat(POOL.byId("fdc-explain-sensor#nope")).isNull();
        assertThat(POOL.byId("sensor_list")).isNull();
    }

    @Test
    void run_이름표에_쓸_인자는_스킬의_필수_인자_전량이다() {
        // 그 조달이 실제로 쓰는 바인드가 아니라 — 그래야 모든 조달이 같은 이름표를 단다.
        QueryPool.Query equipment = POOL.byId("fdc-explain-sensor#equipment_row");
        assertThat(equipment.requiredArgs()).containsExactly("snsr_id");
        assertThat(equipment.dependsOnQuery()).isTrue();
        assertThat(POOL.byId("fdc-trace-reading#equipment_row").requiredArgs())
                .containsExactly("end", "equipment", "param_index", "start");
    }

    @Test
    void 라벨은_그_조달이_채우는_need_의_말이다() {
        // 조회의 이름이 아니라 답에 기여하는 것 — v3 가 뒤집은 방향의 화면상 모습이다.
        assertThat(POOL.byId("fdc-explain-sensor#setup_event_rows").label())
                .isEqualTo("그 설비의 최근 정비 이벤트");
        assertThat(POOL.byId("fdc-explain-sensor#sensor_row").label())
                .startsWith("무엇을 재는 센서인지 외 ");
    }

    @Test
    void 카탈로그는_인자와_앞_조달_의존을_적는다() {
        String catalog = POOL.catalogText();
        assertThat(catalog).contains("fdc-explain-sensor#sensor_row —").contains("(인자: snsr_id)");
        assertThat(catalog).contains("fdc-explain-sensor#equipment_row")
                .contains("※ 앞 조달 결과 필요");
    }

    @Test
    void needs_는_스킬_이름으로_꺼낸다() {
        assertThat(POOL.needsOf("fdc-explain-sensor")).extracting(SkillSpec.SkillNeed::id)
                .contains("sensor_active", "equipment_active");
        assertThat(POOL.knows("fdc-explain-sensor")).isTrue();
        assertThat(POOL.knows("nope")).isFalse();
        assertThat(POOL.needsOf("nope")).isEmpty();
    }

    @Test
    void 스킬이_없으면_풀도_비어_있다() {
        assertThat(QueryPool.of(java.util.List.of()).isEmpty()).isTrue();
    }
}

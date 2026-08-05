package fdc.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.chat.PanelJudge.Narration;
import fdc.agent.chat.PanelJudge.QueryArrival;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.QueryScope;
import fdc.agent.contract.Role;
import fdc.agent.contract.SnapshotIndexEntry;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.skills.NeedsResolver;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 종결 서술 프롬프트 합성 — <b>추론 경로가 남겼을 대화의 재생</b>인지가 합격
 * 기준이다. 절 하나 = 합성 함수 하나는 그대로이나, 결과물은 맥락 문서가 아니라
 * 메시지 배열(계획 → 조달 계획 → act/도착 → 판정 → 규칙 → 지시)이다.
 */
class NarrationPromptTest {

    /**
     * explain-sensor 축소판 — sensor_row 는 {@code table} 저작, equipment_row 는
     * 미저작(FROM 폴백 검증). 실제 spec 과 같은 필드만 채운다.
     */
    private static SkillSpec spec() {
        return new SkillSpec("fdc-explain-sensor", "{snsr_id}",
                List.of("412086 설명해줘"),
                "센서 412086이 무엇을 재고 어느 설비에 속하는지.",
                "FDC_SENSOR",
                List.of(new SkillSpec.SkillInput("snsr_id", true, "조회 키")),
                null,
                List.of(
                        new SkillSpec.SkillNeed("measure_kind", "센서 정체·상태", null,
                                List.of(new SkillSpec.Fill("sensor_row", "SNSR_TYPE_CD"))),
                        new SkillSpec.SkillNeed("owner_equipment", "소속 설비", null,
                                List.of(new SkillSpec.Fill("equipment_row", "EQP_NAME")))),
                List.of(
                        new SkillSpec.SpecQuery("sensor_row", "sql", "fdc_sensor",
                                "SELECT snsr_id, eqp_id, snsr_type_cd, unit_cd, use_yn"
                                        + " FROM fdc_sensor WHERE snsr_id = :id",
                                Map.of("id", new SkillSpec.BindSource("arg", "snsr_id", null, null)),
                                "0행이면 등록되어 있지 않다는 뜻이다."),
                        new SkillSpec.SpecQuery("equipment_row", "sql", null,
                                "SELECT eqp_id, eqp_name, model_cd, vendor, use_yn"
                                        + " FROM fdc_equipment WHERE eqp_id = :eqp",
                                Map.of("eqp", new SkillSpec.BindSource(
                                        "query", null, "sensor_row", "EQP_ID")),
                                null)),
                new SkillSpec.SkillOutput(
                        List.of("비활성 '사유'를 추측한다 — 사유 컬럼은 데이터에 없다"),
                        List.of()),
                null);
    }

    private static List<QueryPool.Query> queries() {
        return QueryPool.of(List.of(spec())).queriesOf("fdc-explain-sensor");
    }

    private static QueryArrival arrival(QueryPool.Query q, List<List<String>> rows,
            List<String> columns) {
        String key = q.queryId() + "__snsr_id=412086";
        SnapshotIndexEntry hit = new SnapshotIndexEntry(
                key, q.label(), "2026-08-01T00:00", columns, rows.size(), null, true);
        ChatDataSnapshot full = new ChatDataSnapshot(
                key, q.label(), "2026-08-01T00:00", columns, rows.size(), rows);
        return new QueryArrival(q, hit, rows.isEmpty() ? null : full);
    }

    /** {@code "조달id.컬럼" → 값들} 로 판정 입력을 준다. 없는 키는 미도착. */
    private static NeedsResolver.Resolution resolve(Map<String, List<String>> cells) {
        return NeedsResolver.resolve(spec().needs(), (queryId, column) -> {
            List<String> values = cells.get(queryId + "." + column);
            return values == null ? null : NeedsResolver.Cell.of(values);
        });
    }

    private static final Map<String, List<String>> ALL_FILLED = Map.of(
            "sensor_row.SNSR_TYPE_CD", List.of("TEMP"),
            "equipment_row.EQP_NAME", List.of("증착기 1호"));

    private static final List<String> SENSOR_COLUMNS =
            List.of("SNSR_ID", "EQP_ID", "SNSR_TYPE_CD", "UNIT_CD", "USE_YN");

    /** 조달 둘 모두 1행 도착, 절차 종결 — 바인드 체인이 살아 있는 정상 경로. */
    private static Narration narration() {
        List<QueryPool.Query> queries = queries();
        return new Narration("fdc-explain-sensor (snsr_id=412086)", "fdc-explain-sensor",
                Map.of("snsr_id", "412086"),
                List.of(
                        arrival(queries.get(0),
                                List.of(List.of("412086", "CVD-01", "TEMP", "C", "Y")),
                                SENSOR_COLUMNS),
                        arrival(queries.get(1),
                                List.of(List.of("CVD-01", "증착기 1호", "CV-800", "AMAT", "Y")),
                                List.of("EQP_ID", "EQP_NAME", "MODEL_CD", "VENDOR", "USE_YN"))),
                resolve(ALL_FILLED));
    }

    private static QueryScope scope() {
        return new QueryScope(null, List.of(new QueryScope.Analysis(
                "a1", "CVD-01", "fdc-explain-sensor", "센서 정보", Map.of("snsr_id", "412086"))));
    }

    @Test
    void 계획_턴에_rephrasing_과_needs_전량이_들어간다() {
        String plan = NarrationPrompt.planTurn(scope(), spec(), narration());

        assertThat(plan).startsWith(
                "분석 대상 — 설비 CVD-01 · 센서 정보 (fdc-explain-sensor; snsr_id=412086).");
        assertThat(plan).contains(
                "이 질문에 답한다는 건 다음을 말하는 것이다: 센서 412086이 무엇을 재고 어느 설비에 속하는지.");
        // 채움 자리까지 적는다 — 어느 표의 어느 컬럼이 그 사실인지는 추론 경로에서
        // 모델이 스스로 정한 것이고, 지금은 정답지에 적혀 있을 뿐이다.
        assertThat(plan).contains("그러려면 알아야 할 것:"
                + "\n- 센서 정체·상태 → fdc_sensor.SNSR_TYPE_CD"
                + "\n- 소속 설비 → fdc_equipment.EQP_NAME");
    }

    @Test
    void 계획의_needs_는_판정_전_전량이라_못_찬_것도_실린다() {
        Narration n = new Narration("fdc-explain-sensor (snsr_id=412086)", "fdc-explain-sensor",
                Map.of("snsr_id", "412086"), narration().arrivals(),
                resolve(Map.of("sensor_row.SNSR_TYPE_CD", List.of("TEMP"))));

        assertThat(NarrationPrompt.planTurn(scope(), spec(), n)).contains("- 소속 설비 → ");
    }

    @Test
    void 조건부_need_는_조건을_달고_조달_수단이_없으면_그렇게_적는다() {
        SkillSpec s = withNeeds(List.of(
                new SkillSpec.SkillNeed("sensor_active", "센서가 쓰이는지", null,
                        List.of(new SkillSpec.Fill("sensor_row", "USE_YN"))),
                new SkillSpec.SkillNeed("equipment_active", "설비가 미사용인지",
                        "sensor_active = N", List.of(new SkillSpec.Fill("equipment_row", "USE_YN"))),
                new SkillSpec.SkillNeed("reason", "비활성 사유", null, List.of())));

        String plan = NarrationPrompt.planTurn(null, s, narration());
        assertThat(plan).contains("- 설비가 미사용인지 (sensor_active = N 일 때만) → fdc_equipment.USE_YN");
        assertThat(plan).contains("- 비활성 사유 → 조달 수단 없음");
    }

    @Test
    void 조달_계획은_의존과_notes_를_적고_SQL_은_안_적는다() {
        String procurement = NarrationPrompt.procurementTurn(spec());

        assertThat(procurement).isEqualTo("조달 계획 2건:"
                + "\n- sensor_row (fdc_sensor) — 인자 snsr_id 로 바로 조회할 수 있다."
                + " 0행이면 등록되어 있지 않다는 뜻이다."
                + "\n- equipment_row (fdc_equipment) — sensor_row.EQP_ID 이(가) 있어야 한다.");
        assertThat(procurement).doesNotContain("SELECT");
    }

    @Test
    void 메시지는_계획_조달계획_act_도착_판정_규칙_지시_순으로_재생된다() {
        List<HistoryMessage> history = List.of(
                new HistoryMessage(Role.USER, "412086 설명해줘"),
                new HistoryMessage(Role.ASSISTANT, "데이터가 도착하면 이어서 설명하겠습니다."));

        List<LlmMessage> m = NarrationPrompt.messages(history, scope(), spec(), narration());

        assertThat(m).hasSize(13);
        assertThat(m.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(m.get(0).content()).contains("분석 어시스턴트");
        assertThat(m.get(1).content()).isEqualTo("412086 설명해줘");
        assertThat(m.get(3).role()).isEqualTo(Role.ASSISTANT);
        assertThat(m.get(3).content()).startsWith("분석 대상 —");
        assertThat(m.get(4).content()).startsWith("조달 계획 2건:");
        // 라운드 1 — 인자만으로 도는 조달
        assertThat(m.get(5).role()).isEqualTo(Role.ASSISTANT);
        assertThat(m.get(5).toolCalls()).hasSize(1);
        assertThat(m.get(5).toolCalls().get(0).name()).isEqualTo(NarrationPrompt.TOOL_NAME);
        assertThat(m.get(6).role()).isEqualTo(Role.TOOL);
        assertThat(m.get(6).toolCallId()).isEqualTo(m.get(5).toolCalls().get(0).id());
        // 가교 — 다음 SQL 에 박힐 값이 어디서 나왔는지
        assertThat(m.get(7).role()).isEqualTo(Role.ASSISTANT);
        assertThat(m.get(7).content()).isEqualTo(
                "sensor_row 도착. 그 값으로 equipment_row 를 이어서 조회한다.");
        // 라운드 2 — 앞 결과를 무는 조달
        assertThat(m.get(8).toolCalls()).hasSize(1);
        assertThat(m.get(9).role()).isEqualTo(Role.TOOL);
        assertThat(m.get(10).content()).startsWith("확인 결과:");
        assertThat(m.get(11).role()).isEqualTo(Role.SYSTEM);
        assertThat(m.get(11).content()).startsWith(NarrationPrompt.NO_INVENTION);
        assertThat(m.get(12).role()).isEqualTo(Role.USER);
        assertThat(m.get(12).content()).isEqualTo(NarrationPrompt.NARRATE_INSTRUCTION);
    }

    @Test
    void act_인자는_needs_와_렌더된_SQL_이고_못_여는_need_는_blocked_로_실린다() {
        List<LlmMessage> m = NarrationPrompt.messages(List.of(), scope(), spec(), narration());

        // 이력이 없으므로 [0]정체성 [1]계획 [2]조달계획 [3]act1 [4]도착1 [5]가교 [6]act2 …
        Map<String, Object> first = m.get(3).toolCalls().get(0).arguments();
        assertThat(stringify(first)).contains("\"needs\"").contains("\"queries\"");
        // 첫 호출에는 아직 못 여는 need 도 실린다 — 사용자가 이미 갖고 있으면
        // 우리가 정한 순서를 기다릴 이유가 없다.
        assertThat(stringify(first)).contains("\"id\":\"owner_equipment\"")
                .contains("\"blocked\":\"sensor_row.EQP_ID 필요\"");
        assertThat(stringify(first)).contains("WHERE snsr_id = 412086");

        Map<String, Object> second = m.get(6).toolCalls().get(0).arguments();
        // 두 번째 호출은 그 라운드 need 만, SQL 은 앞 라운드 값으로 렌더된다.
        assertThat(stringify(second)).doesNotContain("measure_kind").doesNotContain("blocked");
        assertThat(stringify(second)).contains("WHERE eqp_id = 'CVD-01'");
    }

    @Test
    void 도착_데이터는_tool_메시지에_source_와_함께_전량_실린다() {
        List<LlmMessage> m = NarrationPrompt.messages(List.of(), scope(), spec(), narration());

        assertThat(m.get(4).content()).isEqualTo("{\"arrived\":[{\"source\":\"sensor_row\","
                + "\"table\":\"fdc_sensor\","
                + "\"columns\":[\"SNSR_ID\",\"EQP_ID\",\"SNSR_TYPE_CD\",\"UNIT_CD\",\"USE_YN\"],"
                + "\"rows\":[[\"412086\",\"CVD-01\",\"TEMP\",\"C\",\"Y\"]]}]}");
    }

    @Test
    void 데이터는_행_상한_없이_전량_동봉된다() {
        List<List<String>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 23; i++) {
            many.add(List.of("S-" + i, "CVD-01", "TEMP", "C", "Y"));
        }
        Narration n = new Narration("fdc-explain-sensor (snsr_id=412086)", "fdc-explain-sensor",
                Map.of("snsr_id", "412086"),
                List.of(arrival(queries().get(0), many, SENSOR_COLUMNS)),
                resolve(ALL_FILLED));

        List<LlmMessage> m = NarrationPrompt.messages(List.of(), null, spec(), n);
        assertThat(m.get(4).content()).contains("S-0").contains("S-22");
    }

    @Test
    void 판정은_없음_확인과_조달_불가를_구분한다() {
        // sensor_row 는 0행으로 도착(= 없음 확인), equipment_row 는 그 값이 없어 못 돈다.
        Narration n = new Narration("fdc-explain-sensor (snsr_id=412086)", "fdc-explain-sensor",
                Map.of("snsr_id", "412086"),
                List.of(arrival(queries().get(0), List.of(), SENSOR_COLUMNS)),
                resolve(Map.of("sensor_row.SNSR_TYPE_CD", List.of())));

        String verdict = NarrationPrompt.verdictTurn(spec(), n);
        assertThat(verdict).contains("- 조회했으나 없음이 확인됨: 센서 정체·상태");
        assertThat(verdict).contains("- 도착하지 않음: 소속 설비");
        assertThat(verdict).endsWith("더 조달할 것은 없다.");
        assertThat(verdict).doesNotContain("확보:");
    }

    @Test
    void 판정은_찬_것과_조건_미해당을_따로_적는다() {
        SkillSpec s = withNeeds(List.of(
                new SkillSpec.SkillNeed("measure_kind", "센서 정체·상태", null,
                        List.of(new SkillSpec.Fill("sensor_row", "SNSR_TYPE_CD"))),
                new SkillSpec.SkillNeed("equipment_active", "설비가 미사용인지",
                        "measure_kind = FLOW",
                        List.of(new SkillSpec.Fill("equipment_row", "USE_YN")))));
        NeedsResolver.Resolution resolution = NeedsResolver.resolve(s.needs(),
                (queryId, column) -> "sensor_row".equals(queryId)
                        ? NeedsResolver.Cell.of(List.of("TEMP")) : null);
        Narration n = new Narration("fdc-explain-sensor (snsr_id=412086)", "fdc-explain-sensor",
                Map.of("snsr_id", "412086"), narration().arrivals(), resolution);

        String verdict = NarrationPrompt.verdictTurn(s, n);
        assertThat(verdict).contains("- 확보: 센서 정체·상태");
        assertThat(verdict).contains("- 해당 없음: 설비가 미사용인지");
    }

    @Test
    void 서술_규칙은_지어내지_말_것과_하지_말_것뿐이다() {
        String rules = NarrationPrompt.narrationRules(spec());

        assertThat(rules).isEqualTo(NarrationPrompt.NO_INVENTION
                + "\n\n하지 말 것:\n- 비활성 '사유'를 추측한다 — 사유 컬럼은 데이터에 없다");
        // 계획도 판정도 여기 없다 — 채널이 다르다.
        assertThat(rules).doesNotContain("알아야 할 것").doesNotContain("확인 결과");
    }

    @Test
    void spec_이_없어도_계획과_판정은_나온다() {
        // 답의 바닥은 spec 이 아니라 needs 판정이다 — spec 이 어긋나도 그건 남는다.
        List<LlmMessage> m = NarrationPrompt.messages(List.of(), scope(), null, narration());

        assertThat(m.get(1).content()).contains("- 센서 정체·상태").contains("- 소속 설비");
        assertThat(m.get(1).content()).doesNotContain("이 질문에 답한다는 건");
        assertThat(m).noneMatch(x -> x.content() != null && x.content().startsWith("조달 계획"));
        assertThat(m.get(m.size() - 2).content()).isEqualTo(NarrationPrompt.NO_INVENTION);
    }

    @Test
    void scope_가_없으면_run_정체로_분석_대상을_적는다() {
        assertThat(NarrationPrompt.planTurn(null, spec(), narration()))
                .startsWith("분석 대상 — fdc-explain-sensor (fdc-explain-sensor; snsr_id=412086).");
    }

    private static SkillSpec withNeeds(List<SkillSpec.SkillNeed> needs) {
        SkillSpec base = spec();
        return new SkillSpec(base.name(), base.argumentHint(), base.questions(), base.rephrasing(),
                base.anchorTable(), base.inputs(), base.dependencies(), needs, base.queries(),
                base.output(), base.discipline());
    }

    private static String stringify(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package fdc.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.chat.PanelJudge.Narration;
import fdc.agent.chat.PanelJudge.StepArrival;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.QueryScope;
import fdc.agent.contract.Role;
import fdc.agent.contract.SnapshotIndexEntry;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 종결 서술 v3 프롬프트 합성(#51) — 절 하나 = 합성 함수 하나(전부 결정론),
 * 절 헤딩 문자열이 앵커다. 시나리오는 설계 문서(qt1-llm-request.md §1)의
 * explain-sensor 2단계 적재 완료 상태를 따른다.
 */
class NarrationPromptTest {

    /**
     * explain-sensor 축소판 — 1단계는 {@code table} 저작, 2단계는 미저작(FROM 폴백
     * 검증). 실제 spec 과 같은 필드만 채운다.
     */
    private static SkillSpec spec() {
        return new SkillSpec("fdc-explain-sensor", null,
                new SkillSpec.SkillScope("센서", "단일", "상태"),
                "정체·소속 설비·현재 상태", "FDC_SENSOR", null,
                List.of(new SkillSpec.SkillInput("snsr_id", true, "조회 키")),
                null,
                List.of(
                        new SkillSpec.SkillStep("1단계 — 센서 기본 정보", "fdc_sensor",
                                "센서 정체·상태", null,
                                "SELECT snsr_id, eqp_id, snsr_type_cd, unit_cd, use_yn"
                                        + " FROM fdc_sensor WHERE snsr_id = :id",
                                Map.of("id", new SkillSpec.BindSource("arg", "snsr_id", null, null)),
                                null, null),
                        new SkillSpec.SkillStep("2단계 — 소속 설비", null,
                                "소속 설비", null,
                                "SELECT eqp_id, eqp_name, model_cd, vendor, use_yn"
                                        + " FROM fdc_equipment WHERE eqp_id = :eqp",
                                Map.of("eqp", new SkillSpec.BindSource("step", null, 0, "EQP_ID")),
                                null, null)),
                new SkillSpec.SkillOutput(
                        List.of("비활성 '사유'를 추측한다 — 사유 컬럼은 데이터에 없다"),
                        List.of()),
                null);
    }

    private static List<QueryPool.Query> steps() {
        return QueryPool.of(List.of(spec())).stepsOf("fdc-explain-sensor");
    }

    private static StepArrival arrival(QueryPool.Query q, List<List<String>> rows,
            List<String> columns) {
        String key = q.queryId() + "__snsr_id=412086";
        SnapshotIndexEntry hit = new SnapshotIndexEntry(
                key, q.title(), "2026-08-01T00:00", columns, rows.size(), null, true);
        ChatDataSnapshot full = new ChatDataSnapshot(
                key, q.title(), "2026-08-01T00:00", columns, rows.size(), rows);
        return new StepArrival(q, hit, rows.isEmpty() ? null : full);
    }

    /** 설계 문서 §1 시나리오 — 2단계 모두 1행 도착, 절차 종결. */
    private static Narration narration() {
        List<QueryPool.Query> steps = steps();
        return new Narration("fdc-explain-sensor (snsr_id=412086)", "fdc-explain-sensor",
                Map.of("snsr_id", "412086"),
                List.of(
                        arrival(steps.get(0),
                                List.of(List.of("412086", "CVD-01", "TEMP", "C", "Y")),
                                List.of("SNSR_ID", "EQP_ID", "SNSR_TYPE_CD", "UNIT_CD", "USE_YN")),
                        arrival(steps.get(1),
                                List.of(List.of("CVD-01", "증착기 1호", "CV-800", "AMAT", "Y")),
                                List.of("EQP_ID", "EQP_NAME", "MODEL_CD", "VENDOR", "USE_YN"))));
    }

    private static QueryScope scope() {
        return new QueryScope(null, List.of(new QueryScope.Analysis(
                "a1", "CVD-01", "fdc-explain-sensor", "센서 정보", Map.of("snsr_id", "412086"))));
    }

    @Test
    void 맥락_섹션이_세_절로_합성된다() {
        String context = NarrationPrompt.contextSection(scope(), spec(), narration());

        assertThat(context).contains(NarrationPrompt.SECTION_TARGET
                + "\n- 설비 CVD-01 · 센서 정보 (fdc-explain-sensor; snsr_id=412086)");
        assertThat(context).contains(NarrationPrompt.SECTION_DATA);
        assertThat(context).contains("여기 없는 값은 조회되지 않은 것이다");
        assertThat(context).contains(NarrationPrompt.SECTION_GUIDE
                + "\n조회한 데이터로 센서의 정체·소속 설비·현재 상태를 설명한다.");
        assertThat(context).contains("반드시 포함 (질문이 특정 항목만 묻는 게 아니면):"
                + "\n- 센서 정체·상태\n- 소속 설비");
        assertThat(context).contains("하지 말 것:\n- 비활성 '사유'를 추측한다");
    }

    @Test
    void 데이터_블록_헤딩은_원천_테이블명이고_FROM_폴백이_동작한다() {
        String context = NarrationPrompt.contextSection(scope(), spec(), narration());

        // 1단계는 spec steps[].table, 2단계는 table 미저작 → SQL FROM 파싱 폴백.
        assertThat(context).contains("## fdc_sensor — 센서 기본 정보 (1행)");
        assertThat(context).contains("## fdc_equipment — 소속 설비 (1행)");
        // 전량 동봉 — FE 계약 표 모양 JSON(columns+rows), 셀은 문자열 유지.
        assertThat(context).contains(
                "{\"columns\":[\"SNSR_ID\",\"EQP_ID\",\"SNSR_TYPE_CD\",\"UNIT_CD\",\"USE_YN\"],"
                        + "\"rows\":[[\"412086\",\"CVD-01\",\"TEMP\",\"C\",\"Y\"]]}");
    }

    @Test
    void 데이터는_행_상한_없이_전량_동봉된다() {
        List<QueryPool.Query> steps = steps();
        List<List<String>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 23; i++) {
            many.add(List.of("S-" + i, "CVD-01", "TEMP", "C", "Y"));
        }
        Narration n = new Narration("fdc-explain-sensor (snsr_id=412086)", "fdc-explain-sensor",
                Map.of("snsr_id", "412086"),
                List.of(arrival(steps.get(0), many,
                        List.of("SNSR_ID", "EQP_ID", "SNSR_TYPE_CD", "UNIT_CD", "USE_YN"))));

        String context = NarrationPrompt.contextSection(null, spec(), n);
        assertThat(context).contains("(23행)").contains("S-0").contains("S-22");
        assertThat(context).doesNotContain("외 ");
    }

    @Test
    void 영행_스텝은_없음_확인_블록으로_남는다() {
        List<QueryPool.Query> steps = steps();
        Narration n = new Narration("fdc-explain-sensor (snsr_id=412086)", "fdc-explain-sensor",
                Map.of("snsr_id", "412086"),
                List.of(arrival(steps.get(0), List.of(),
                        List.of("SNSR_ID", "EQP_ID", "SNSR_TYPE_CD", "UNIT_CD", "USE_YN"))));

        String context = NarrationPrompt.contextSection(null, spec(), n);
        assertThat(context).contains("## fdc_sensor — 센서 기본 정보 (0행)");
        assertThat(context).contains("없음이 확인됐다");
        assertThat(context).doesNotContain("```json");
    }

    @Test
    void 메시지는_정체성_이력_맥락_지시_순서로_user_마감이다() {
        List<HistoryMessage> history = List.of(
                new HistoryMessage(Role.USER, "412086 설명해줘"),
                new HistoryMessage(Role.ASSISTANT, "데이터가 도착하면 이어서 설명하겠습니다."));

        List<LlmMessage> messages =
                NarrationPrompt.messages(history, scope(), spec(), narration());

        assertThat(messages).hasSize(5);
        assertThat(messages.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(messages.get(0).content()).contains("분석 어시스턴트");
        assertThat(messages.get(1).content()).isEqualTo("412086 설명해줘");
        assertThat(messages.get(2).role()).isEqualTo(Role.ASSISTANT);
        assertThat(messages.get(3).role()).isEqualTo(Role.SYSTEM);
        assertThat(messages.get(3).content()).startsWith(NarrationPrompt.SECTION_TARGET);
        assertThat(messages.get(4).role()).isEqualTo(Role.USER);
        assertThat(messages.get(4).content()).isEqualTo(NarrationPrompt.NARRATE_INSTRUCTION);
    }

    @Test
    void scope_가_없으면_run_정체로_질의_대상을_적는다() {
        String context = NarrationPrompt.contextSection(null, spec(), narration());
        assertThat(context).contains(NarrationPrompt.SECTION_TARGET
                + "\n- 정체·소속 설비·현재 상태 (fdc-explain-sensor; snsr_id=412086)");
    }

    @Test
    void spec_이_없으면_가이드_절_없이_데이터만으로_합성한다() {
        String context = NarrationPrompt.contextSection(scope(), null, narration());
        assertThat(context).contains(NarrationPrompt.SECTION_DATA);
        assertThat(context).doesNotContain(NarrationPrompt.SECTION_GUIDE);
    }
}

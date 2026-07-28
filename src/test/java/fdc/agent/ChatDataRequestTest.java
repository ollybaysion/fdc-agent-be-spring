package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.DataRequest;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmToolSpec;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.skills.SkillRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 조달 요청은 <b>등재된 풀에서만</b> 나가고, SQL·조회 키·기대 컬럼은 BE 가 만든다.
 * 이어가기는 도착한 스냅샷에서 값을 읽고, 0행이면 거기서 끝난다.
 *
 * <p>스크립트된 LLM 이 request_data 를 한 번 부르고, 되먹은 요약을 그대로 최종 답으로
 * 돌려준다 — 그래서 {@code result.text()} 가 곧 모델이 본 되먹임이다.
 */
class ChatDataRequestTest {

    private static final String SENSOR_KEY_0 = "fdc-explain-sensor#0__snsr_id=S-0004";
    private static final String SENSOR_KEY_1 = "fdc-explain-sensor#1__snsr_id=S-0004";

    /** 툴을 한 번 부르고, 되먹은 요약을 최종 답으로 돌려주는 fake. */
    private static LlmClient calls(Map<String, Object> args) {
        return (List<LlmMessage> messages, List<LlmToolSpec> tools) -> {
            LlmMessage last = messages.get(messages.size() - 1);
            if (last.role() == Role.TOOL) {
                return new LlmTurn.Final(last.content());
            }
            return new LlmTurn.ToolCalls(List.of(new LlmToolCall("c1", "request_data", args)));
        };
    }

    private static Map<String, Object> request(String queryId, Map<String, String> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queryId", queryId);
        out.put("args", args);
        return out;
    }

    private static AgentResult run(Map<String, Object> toolArgs, List<ChatDataSnapshot> snapshots) {
        return new ChatAgent(calls(toolArgs), SkillRegistry.FIXTURE_SKILL_QUERY)
                .run(List.of(new HistoryMessage(Role.USER, "조달 요청해줘")), snapshots);
    }

    private static ChatDataSnapshot arrived(String key, List<String> cols, List<List<String>> rows) {
        return new ChatDataSnapshot(key, "앞 단계", "2026-07-28T00:00", cols, rows.size(), rows);
    }

    @Test
    void 풀에서_고른_조회는_실행_가능한_SQL_로_나간다() {
        AgentResult result = run(request("fdc-explain-sensor#0", Map.of("snsr_id", "S-0004")), null);

        assertThat(result.dataRequests()).hasSize(1);
        DataRequest req = result.dataRequests().get(0);
        // 키도 SQL 도 컬럼도 BE 가 만든다 — 모델은 고르고 값만 채웠다.
        assertThat(req.queryKey()).isEqualTo(SENSOR_KEY_0);
        assertThat(req.sql()).contains("FROM fdc_sensor").contains("snsr_id = 'S-0004'")
                .doesNotContain(":id");
        assertThat(req.columns()).containsExactly("SNSR_ID", "EQP_ID", "SNSR_TYPE_CD", "UNIT_CD", "USE_YN");
        assertThat(req.label()).contains("snsr_id=S-0004");
    }

    @Test
    void 풀에_없는_조회는_카드로_나가지_않는다() {
        AgentResult result = run(request("sensor_list", Map.of("equipment_id", "CVD-01")), null);

        assertThat(result.dataRequests()).isEmpty();
        assertThat(result.text()).contains("등재된 조회가 아닙니다")
                .contains("fdc-explain-sensor#0"); // 목록을 되먹여 다시 고르게 한다
    }

    @Test
    void 앞_단계가_도착해_있으면_그_값으로_이어간다() {
        AgentResult result = run(
                request("fdc-explain-sensor#1", Map.of("snsr_id", "S-0004")),
                List.of(arrived(SENSOR_KEY_0, List.of("SNSR_ID", "EQP_ID"),
                        List.of(List.of("S-0004", "CVD-01")))));

        assertThat(result.dataRequests()).hasSize(1);
        // eqp 는 모델이 아니라 붙여넣은 표에서 나왔다.
        assertThat(result.dataRequests().get(0).sql()).contains("eqp_id = 'CVD-01'");
        assertThat(result.dataRequests().get(0).queryKey()).isEqualTo(SENSOR_KEY_1);
    }

    @Test
    void 이어가기에서_인자를_빼먹어도_진행_중인_절차가_하나면_붙는다() {
        Map<String, Object> noArgs = new LinkedHashMap<>();
        noArgs.put("queryId", "fdc-explain-sensor#1");
        noArgs.put("args", Map.of());

        AgentResult result = run(noArgs,
                List.of(arrived(SENSOR_KEY_0, List.of("EQP_ID"), List.of(List.of("CVD-01")))));

        assertThat(result.dataRequests()).hasSize(1);
        assertThat(result.dataRequests().get(0).queryKey()).isEqualTo(SENSOR_KEY_1);
    }

    @Test
    void 앞_단계가_안_왔으면_그_단계를_먼저_요청하라고_되먹인다() {
        AgentResult result = run(request("fdc-explain-sensor#1", Map.of("snsr_id", "S-0004")), null);

        assertThat(result.dataRequests()).isEmpty();
        assertThat(result.text()).contains("1단계 결과가 먼저 필요합니다")
                .contains("fdc-explain-sensor#0");
    }

    @Test
    void 앞_단계가_0행이면_이어가지_않고_없다는_사실로_답하게_한다() {
        // 0행은 "아직 안 왔다"가 아니다 — 절차는 여기서 정상 종료다.
        ChatDataSnapshot empty = new ChatDataSnapshot(
                SENSOR_KEY_0, "1단계", "2026-07-28T00:00", List.of("SNSR_ID", "EQP_ID"), 0, List.of());

        AgentResult result = run(request("fdc-explain-sensor#1", Map.of("snsr_id", "S-0004")),
                List.of(empty));

        assertThat(result.dataRequests()).isEmpty();
        assertThat(result.text()).contains("0행").contains("데이터가 없다는 사실");
    }

    @Test
    void 앞_단계가_여러_행이면_고르라고_되먹이고_고른_값만_통과시킨다() {
        List<ChatDataSnapshot> twoRows = List.of(arrived(SENSOR_KEY_0,
                List.of("SNSR_ID", "EQP_ID"),
                List.of(List.of("S-0004", "CVD-01"), List.of("S-0004", "ETCH-01"))));

        AgentResult ambiguous = run(request("fdc-explain-sensor#1", Map.of("snsr_id", "S-0004")), twoRows);
        assertThat(ambiguous.dataRequests()).isEmpty();
        assertThat(ambiguous.text()).contains("여러 값입니다").contains("CVD-01, ETCH-01");

        Map<String, Object> picked = request("fdc-explain-sensor#1", Map.of("snsr_id", "S-0004"));
        picked.put("pick", Map.of("EQP_ID", "ETCH-01"));
        AgentResult chosen = run(picked, twoRows);
        assertThat(chosen.dataRequests()).hasSize(1);
        assertThat(chosen.dataRequests().get(0).sql()).contains("eqp_id = 'ETCH-01'");
    }

    @Test
    void 표에_없는_값은_pick_으로도_넣지_못한다() {
        // 갈림길은 모델에 맡기되 창작은 막는다.
        Map<String, Object> invented = request("fdc-explain-sensor#1", Map.of("snsr_id", "S-0004"));
        invented.put("pick", Map.of("EQP_ID", "NOT-REAL"));

        AgentResult result = run(invented, List.of(arrived(SENSOR_KEY_0,
                List.of("SNSR_ID", "EQP_ID"),
                List.of(List.of("S-0004", "CVD-01"), List.of("S-0004", "ETCH-01")))));

        assertThat(result.dataRequests()).isEmpty();
        assertThat(result.text()).contains("없는 값입니다").contains("가능한 값: CVD-01, ETCH-01");
    }

    @Test
    void 이미_도착한_데이터는_다시_요청하지_않는다() {
        AgentResult result = run(request("fdc-explain-sensor#0", Map.of("snsr_id", "S-0004")),
                List.of(arrived(SENSOR_KEY_0, List.of("SNSR_ID"), List.of(List.of("S-0004")))));

        assertThat(result.dataRequests()).isEmpty();
        assertThat(result.text()).contains("이미 도착한 데이터");
    }

    @Test
    void 결과_0행으로_확인된_조회도_다시_요청하지_않는다() {
        ChatDataSnapshot empty = new ChatDataSnapshot(
                SENSOR_KEY_0, "1단계", "2026-07-28T00:00", List.of("SNSR_ID"), 0, List.of());

        AgentResult result = run(request("fdc-explain-sensor#0", Map.of("snsr_id", "S-0004")),
                List.of(empty));

        assertThat(result.dataRequests()).isEmpty();
        assertThat(result.text()).contains("이미 0행으로 확인됐습니다");
    }

    @Test
    void 키만_있고_내용이_안_온_항목은_다시_요청할_수_있다() {
        // 억제 기준은 "키가 있나"가 아니라 "도착했나"다 — 아니면 영영 못 받는다.
        ChatDataSnapshot catalogOnly = new ChatDataSnapshot(
                SENSOR_KEY_0, "1단계", "2026-07-28T00:00", List.of("SNSR_ID"), 5, null);

        AgentResult result = run(request("fdc-explain-sensor#0", Map.of("snsr_id", "S-0004")),
                List.of(catalogOnly));

        assertThat(result.dataRequests()).hasSize(1);
    }

    @Test
    void 필수_인자가_없으면_요청하지_않고_사유를_되먹인다() {
        AgentResult result = run(request("fdc-trace-reading#0", Map.of("equipment", "CVD-01")), null);

        assertThat(result.dataRequests()).isEmpty();
        assertThat(result.text()).contains("인자가 모자라").contains("param_index");
    }

    @Test
    void 인자를_JSON_문자열로_보내도_받는다() {
        // 중첩 객체를 스키마대로 못 내는 모델이 통째로 따옴표에 싸서 보낸다.
        Map<String, Object> stringified = new LinkedHashMap<>();
        stringified.put("queryId", "fdc-explain-sensor#0");
        stringified.put("args", "{\"snsr_id\": \"S-0004\"}");

        assertThat(run(stringified, null).dataRequests())
                .singleElement()
                .extracting(DataRequest::queryKey).isEqualTo(SENSOR_KEY_0);
    }

    @Test
    void 진행_상황이_맥락에_실려_다음_걸음을_알려준다() {
        List<String> systems = new java.util.ArrayList<>();
        LlmClient capture = (messages, tools) -> {
            messages.stream().filter(m -> m.role() == Role.SYSTEM)
                    .forEach(m -> systems.add(m.content()));
            return new LlmTurn.Final("ok");
        };
        new ChatAgent(capture, SkillRegistry.FIXTURE_SKILL_QUERY).run(
                List.of(new HistoryMessage(Role.USER, "등록 완료")),
                List.of(arrived(SENSOR_KEY_0, List.of("SNSR_ID", "EQP_ID"),
                        List.of(List.of("S-0004", "CVD-01")))));

        assertThat(String.join("\n", systems))
                .contains("[조회 절차 진행 상황]")
                .contains("queryId=\"fdc-explain-sensor#1\"");
    }
}

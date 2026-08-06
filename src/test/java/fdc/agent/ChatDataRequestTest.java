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
 * {@code retrieve_data} — <b>need 단위</b> 조달 왕복. 모델이 대는 것은 절차(스킬)와
 * 인자뿐이고, 무엇을 조회할지는 서버가 판정으로 정한다. SQL·조회 키·기대 컬럼도 BE 가
 * 만든다. 이어가기는 도착한 스냅샷에서 값을 읽고, 0행이면 거기서 끝난다.
 *
 * <p>스크립트된 LLM 이 툴을 한 번 부르고 되먹은 원장을 그대로 최종 답으로 돌려준다 —
 * 그래서 {@code result.text()} 가 곧 모델이 본 JSON 이다.
 */
class ChatDataRequestTest {

    private static final String SENSOR_KEY = "fdc-explain-sensor#sensor_row__snsr_id=S-0004";
    private static final String EQUIPMENT_KEY = "fdc-explain-sensor#equipment_row__snsr_id=S-0004";

    /** 툴을 한 번 부르고, 되먹은 원장을 최종 답으로 돌려주는 fake. */
    private static LlmClient calls(Map<String, Object> args) {
        return (List<LlmMessage> messages, List<LlmToolSpec> tools) -> {
            LlmMessage last = messages.get(messages.size() - 1);
            if (last.role() == Role.TOOL) {
                return new LlmTurn.Final(last.content());
            }
            return new LlmTurn.ToolCalls(List.of(new LlmToolCall("c1", "retrieve_data", args)));
        };
    }

    private static Map<String, Object> request(String skill, Map<String, String> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skill", skill);
        out.put("args", args);
        return out;
    }

    private static AgentResult run(Map<String, Object> toolArgs, List<ChatDataSnapshot> snapshots) {
        return new ChatAgent(calls(toolArgs), SkillRegistry.FIXTURE_SKILL_QUERY)
                .run(List.of(new HistoryMessage(Role.USER, "조달 요청해줘")), snapshots);
    }

    private static ChatDataSnapshot arrived(String key, List<String> cols, List<List<String>> rows) {
        return new ChatDataSnapshot(key, "조달", "2026-07-28T00:00", cols, rows.size(), rows);
    }

    @Test
    void 절차를_대면_지금_열_수_있는_조달이_SQL_로_나간다() {
        // 무엇을 먼저 돌릴지는 모델이 아니라 판정이 정한다 — 아직 아무것도 없으니
        // 인자만으로 도는 sensor_row 하나다.
        AgentResult result = run(request("fdc-explain-sensor", Map.of("snsr_id", "S-0004")), null);

        assertThat(result.dataRequests()).hasSize(1);
        DataRequest req = result.dataRequests().get(0);
        assertThat(req.queryKey()).isEqualTo(SENSOR_KEY);
        assertThat(req.sql()).contains("FROM fdc_sensor").contains("snsr_id = 'S-0004'")
                .doesNotContain(":id");
        assertThat(req.columns())
                .containsExactly("SNSR_ID", "EQP_ID", "SNSR_TYPE_CD", "UNIT_CD", "USE_YN");
        // 앞 조달을 무는 것들은 사유와 함께 잠긴 채로 함께 돌아온다.
        assertThat(result.text()).contains("\"requested\"").contains("\"blocked\"")
                .contains("equipment_row").contains("먼저 필요합니다");
    }

    @Test
    void 등재되지_않은_스킬은_카드로_나가지_않는다() {
        AgentResult result = run(request("no-such-skill", Map.of("snsr_id", "S-0004")), null);

        assertThat(result.dataRequests()).isEmpty();
        assertThat(result.text()).contains("등재된 스킬이 아닙니다")
                .contains("fdc-explain-sensor"); // 목록을 되먹여 다시 고르게 한다
    }

    @Test
    void 앞_조달이_도착해_있으면_그_값으로_이어간다() {
        AgentResult result = run(
                request("fdc-explain-sensor", Map.of("snsr_id", "S-0004")),
                List.of(arrived(SENSOR_KEY, List.of("SNSR_ID", "EQP_ID", "USE_YN"),
                        List.of(List.of("S-0004", "CVD-01", "Y")))));

        // eqp 는 모델이 아니라 붙여넣은 표에서 나왔다.
        assertThat(result.dataRequests())
                .anySatisfy(req -> assertThat(req.sql()).contains("eqp_id = 'CVD-01'"));
        assertThat(result.dataRequests())
                .extracting(DataRequest::queryKey).contains(EQUIPMENT_KEY);
    }

    @Test
    void 이어가기에서_인자를_빼먹어도_진행_중인_절차가_하나면_붙는다() {
        Map<String, Object> noArgs = new LinkedHashMap<>();
        noArgs.put("skill", "fdc-explain-sensor");
        noArgs.put("args", Map.of());

        AgentResult result = run(noArgs,
                List.of(arrived(SENSOR_KEY, List.of("EQP_ID"), List.of(List.of("CVD-01")))));

        assertThat(result.dataRequests())
                .extracting(DataRequest::queryKey).contains(EQUIPMENT_KEY);
    }

    @Test
    void 앞_조달이_0행이면_이어가지_않고_없다는_사실로_답하게_한다() {
        // 0행은 "아직 안 왔다"가 아니다 — 절차는 여기서 정상 종료다.
        ChatDataSnapshot empty = new ChatDataSnapshot(
                SENSOR_KEY, "1단계", "2026-07-28T00:00", List.of("SNSR_ID", "EQP_ID"), 0, List.of());

        AgentResult result = run(request("fdc-explain-sensor", Map.of("snsr_id", "S-0004")),
                List.of(empty));

        assertThat(result.dataRequests()).isEmpty();
        assertThat(result.text()).contains("\"outcome\":\"unanswerable\"");
    }

    @Test
    void 이미_도착한_사실은_다시_요청하지_않고_값으로_돌아온다() {
        AgentResult result = run(request("fdc-explain-sensor", Map.of("snsr_id", "S-0004")),
                List.of(arrived(SENSOR_KEY, List.of("SNSR_ID", "SNSR_TYPE_CD", "UNIT_CD"),
                        List.of(List.of("S-0004", "FLOW", "SCCM")))));

        assertThat(result.dataRequests())
                .extracting(DataRequest::queryKey).doesNotContain(SENSOR_KEY);
        assertThat(result.text()).contains("\"arrived\"")
                .contains("무엇을 재는 센서인지").contains("FLOW");
    }

    @Test
    void 키만_있고_내용이_안_온_항목은_다시_요청할_수_있다() {
        // 억제 기준은 "키가 있나"가 아니라 "도착했나"다 — 아니면 영영 못 받는다.
        ChatDataSnapshot catalogOnly = new ChatDataSnapshot(
                SENSOR_KEY, "1단계", "2026-07-28T00:00", List.of("SNSR_ID"), 5, null);

        AgentResult result = run(request("fdc-explain-sensor", Map.of("snsr_id", "S-0004")),
                List.of(catalogOnly));

        assertThat(result.dataRequests())
                .extracting(DataRequest::queryKey).contains(SENSOR_KEY);
    }

    @Test
    void needs_를_특정하면_그것을_채우는_조달만_연다() {
        // 모델의 재량은 "무엇이 궁금한가"까지 — 무엇을 돌릴 수 있는가는 결정론이다.
        Map<String, Object> narrowed = request("fdc-explain-sensor", Map.of("snsr_id", "S-0004"));
        narrowed.put("needs", List.of("recent_events"));

        AgentResult result = run(narrowed,
                List.of(arrived(SENSOR_KEY, List.of("EQP_ID"), List.of(List.of("CVD-01")))));

        assertThat(result.dataRequests())
                .extracting(DataRequest::queryKey)
                .containsExactly("fdc-explain-sensor#setup_event_rows__snsr_id=S-0004");
    }

    @Test
    void 필수_인자가_없으면_요청하지_않고_사유를_되먹인다() {
        AgentResult result = run(
                request("fdc-trace-reading", Map.of("equipment", "CVD-01")), null);

        assertThat(result.dataRequests()).isEmpty();
        assertThat(result.text()).contains("인자가 모자라").contains("param_index");
    }

    @Test
    void 인자를_JSON_문자열로_보내도_받는다() {
        // 중첩 객체를 스키마대로 못 내는 모델이 통째로 따옴표에 싸서 보낸다.
        Map<String, Object> stringified = new LinkedHashMap<>();
        stringified.put("skill", "fdc-explain-sensor");
        stringified.put("args", "{\"snsr_id\": \"S-0004\"}");

        assertThat(run(stringified, null).dataRequests())
                .singleElement()
                .extracting(DataRequest::queryKey).isEqualTo(SENSOR_KEY);
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
                List.of(arrived(SENSOR_KEY, List.of("SNSR_ID", "EQP_ID"),
                        List.of(List.of("S-0004", "CVD-01")))));

        assertThat(String.join("\n", systems))
                .contains("[조회 절차 진행 상황]")
                .contains("skill=\"fdc-explain-sensor\"");
    }
}

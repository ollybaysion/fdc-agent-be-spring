package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.FormContext;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.FinishReason;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmToolSpec;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.skills.SkillRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 프롬프트 조립 규율 — 시스템 프롬프트의 툴 규칙은 <b>이번 요청에 실제로 붙은 툴</b>에서만
 * 나오고, 맥락 섹션은 특정 스킬 이름에 매이지 않는다.
 */
class ChatPromptTest {

    /** 첫 호출의 system 메시지만 붙잡는 가짜 LLM. */
    private static final class CaptureSystem implements LlmClient {
        final List<String> systems = new ArrayList<>();
        private final LlmTurn turn;

        CaptureSystem(LlmTurn turn) {
            this.turn = turn;
        }

        @Override
        public LlmTurn next(List<LlmMessage> messages, List<LlmToolSpec> tools) {
            systems.add(String.join("\n", messages.stream()
                    .filter(m -> m.role() == Role.SYSTEM)
                    .map(m -> m.content() != null ? m.content() : "")
                    .toList()));
            return turn;
        }
    }

    private static ChatAgent agent(LlmClient llm) {
        return new ChatAgent(llm, SkillRegistry.FIXTURE_SKILL_QUERY);
    }

    private static List<HistoryMessage> ask(String text) {
        return List.of(new HistoryMessage(Role.USER, text));
    }

    private static ChatDataSnapshot pinned() {
        return new ChatDataSnapshot(
                "sensor_list", "챔버별 센서", "2026-07-28T00:00",
                List.of("CHAMBER", "SENSOR"), 1, List.of(List.of("챔버A", "온도")));
    }

    @Test
    void 붙여넣은_표가_없으면_query_snapshot_규칙이_프롬프트에_없다() {
        // 툴이 안 붙었는데 규칙만 남으면, 없는 툴을 쓰라고 지시하는 프롬프트가 된다.
        CaptureSystem llm = new CaptureSystem(new LlmTurn.Final("ok"));
        agent(llm).run(ask("안녕하세요"), null);

        assertThat(llm.systems.get(0)).doesNotContain("query_snapshot");
    }

    @Test
    void 붙여넣은_표가_있으면_query_snapshot_규칙이_함께_붙는다() {
        CaptureSystem llm = new CaptureSystem(new LlmTurn.Final("ok"));
        agent(llm).run(ask("안녕하세요"), null, List.of(pinned()));

        assertThat(llm.systems.get(0)).contains("[도구 사용 규칙]").contains("query_snapshot");
    }

    @Test
    void 수집_툴의_규칙도_툴에서_나온다() {
        // request_data·request_input 은 항상 붙으므로 규칙도 항상 있다 —
        // 다만 그 문장의 출처는 시스템 프롬프트 상수가 아니라 툴 자신이다.
        CaptureSystem llm = new CaptureSystem(new LlmTurn.Final("ok"));
        agent(llm).run(ask("안녕하세요"), null);

        assertThat(llm.systems.get(0)).contains("request_data").contains("request_input");
    }

    @Test
    void 폼_섹션은_특정_스킬_이름을_박지_않는다() {
        // 스킬 목록은 akg 허브에서 런타임에 온다(#8) — 이름을 박으면 그 스킬이 없는
        // 배포에서 없는 툴을 부르라고 지시하게 된다.
        FormContext form = new FormContext(
                List.of(new FormContext.ContextRow("ETCH-01",
                        List.of(new FormContext.Chamber(List.of(new FormContext.Sensor("5")))))),
                new FormContext.TimeRange("2026-05-01", "2026-05-07"));
        CaptureSystem llm = new CaptureSystem(new LlmTurn.Final("ok"));
        agent(llm).run(ask("분석해줘"), form);

        String prompt = llm.systems.get(0);
        assertThat(prompt).contains("분석 대상").contains("PARAM_INDEX: 5");
        assertThat(prompt).doesNotContain("fdc_trace_reading");
    }

    @Test
    void 스텝_한도에_걸리면_툴_요약_대신_안내를_돌려준다() {
        // 마지막 툴 요약을 그대로 돌려주면 스킬의 [출력 지침]·[하지 말 것] 같은
        // 내부 지시문이 사용자 화면에 그대로 나간다.
        LlmClient neverFinishes = (messages, tools) -> new LlmTurn.ToolCalls(List.of(
                new LlmToolCall("c1", "fdc_explain_sensor", Map.of("snsr_id", "S-0004"))));

        AgentResult result = agent(neverFinishes).run(ask("S-0004 설명해줘"), null);

        assertThat(result.finishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(result.text()).doesNotContain("[출력 지침]").doesNotContain("[하지 말 것]");
        assertThat(result.text()).contains("질문을 조금 더 좁혀서");
        // 그때까지 조회한 표는 그대로 들려 보낸다 — 헛수고로 만들지 않는다.
        assertThat(result.tables()).isNotEmpty();
    }
}

package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.InputRequest;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 스칼라 입력 요청 왕복(#23) — request_input 툴이 inputRequests 로 수집되고,
 * 회신된 inputs 는 프롬프트에 주입되며 같은 (skill,key) 재요청은 억제된다.
 */
class ChatInputRequestTest {

    /** 받은 messages·tools 를 포착하고 지정 turn 을 순서대로 반환하는 가짜 LLM. */
    private static final class CaptureLlm implements LlmClient {
        final List<String> seen = new ArrayList<>();
        final List<List<LlmToolSpec>> seenTools = new ArrayList<>();
        private final List<LlmTurn> turns;
        private final AtomicInteger i = new AtomicInteger();

        CaptureLlm(List<LlmTurn> turns) {
            this.turns = turns;
        }

        @Override
        public LlmTurn next(List<LlmMessage> messages, List<LlmToolSpec> tools) {
            seen.add(String.join("\n", messages.stream()
                    .map(m -> m.role().wire() + ":" + (m.content() != null ? m.content() : ""))
                    .toList()));
            seenTools.add(tools);
            return turns.get(Math.min(i.getAndIncrement(), turns.size() - 1));
        }
    }

    private static ChatAgent agent(LlmClient llm) {
        return new ChatAgent(llm, SkillRegistry.FIXTURE_SKILL_QUERY);
    }

    private static LlmToolCall requestInput(Map<String, Object> args) {
        return new LlmToolCall("c1", "request_input", args);
    }

    @Test
    void request_input_툴이_LLM에_노출된다() {
        CaptureLlm llm = new CaptureLlm(List.of(new LlmTurn.Final("ok")));
        agent(llm).run(List.of(new HistoryMessage(Role.USER, "CVD-01 측정 분석해줘")), null);
        assertThat(llm.seenTools.get(0)).anyMatch(t -> t.name().equals("request_input"));
    }

    @Test
    void request_input_호출은_inputRequests로_수집된다() {
        CaptureLlm llm = new CaptureLlm(List.of(
                new LlmTurn.ToolCalls(List.of(requestInput(Map.of(
                        "skill", "fdc_trace_reading",
                        "key", "param_index",
                        "label", "PARAM_INDEX",
                        "description", "센서 파라미터 인덱스")))),
                new LlmTurn.Final("PARAM_INDEX 를 입력해 주세요.")));
        AgentResult result = agent(llm).run(
                List.of(new HistoryMessage(Role.USER, "CVD-01 측정 분석해줘")), null);

        assertThat(result.inputRequests()).hasSize(1);
        InputRequest req = result.inputRequests().get(0);
        assertThat(req.skill()).isEqualTo("fdc_trace_reading");
        assertThat(req.key()).isEqualTo("param_index");
        assertThat(req.label()).isEqualTo("PARAM_INDEX");
        assertThat(req.description()).isEqualTo("센서 파라미터 인덱스");
    }

    @Test
    void skill_key_label_없는_요청은_수집되지_않는다() {
        CaptureLlm llm = new CaptureLlm(List.of(
                new LlmTurn.ToolCalls(List.of(requestInput(Map.of("label", "PARAM_INDEX")))),
                new LlmTurn.Final("done")));
        AgentResult result = agent(llm).run(
                List.of(new HistoryMessage(Role.USER, "CVD-01 측정 분석해줘")), null);
        assertThat(result.inputRequests()).isEmpty();
    }

    @Test
    void 제공된_입력은_프롬프트에_주입되고_같은_입력_재요청은_억제된다() {
        // LLM 이 (억지로) 같은 입력을 또 요청해도, 이미 제공됐으면 카드로 안 나간다.
        CaptureLlm llm = new CaptureLlm(List.of(
                new LlmTurn.ToolCalls(List.of(requestInput(Map.of(
                        "skill", "fdc_trace_reading", "key", "param_index", "label", "PARAM_INDEX")))),
                new LlmTurn.Final("분석을 이어갑니다.")));
        Map<String, Map<String, String>> inputs = Map.of(
                "fdc_trace_reading", Map.of("param_index", "7"));

        AgentResult result = agent(llm).run(
                List.of(new HistoryMessage(Role.USER, "CVD-01 측정 분석해줘")), null, null, inputs);

        // 제공된 값이 프롬프트(마지막 user 메시지)에 주입된다.
        assertThat(llm.seen.get(0)).contains("제공된 입력").contains("param_index").contains("7");
        // 이미 제공됐으므로 재요청은 카드로 안 나간다(억제).
        assertThat(result.inputRequests()).isEmpty();
    }
}

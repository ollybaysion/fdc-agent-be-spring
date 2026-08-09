package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.ChoiceOption;
import fdc.agent.contract.ChoiceRequest;
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
 * 선택 요청 왕복(#53) — choice_request 툴이 choiceRequests 로 수집되고, 문자열
 * 아이템은 {label} 로 정규화되며, 옵션이 모자라거나 턴 내 같은 question 이 재요청되면
 * 억제된다.
 */
class ChatChoiceRequestTest {

    /** 받은 tools 를 포착하고 지정 turn 을 순서대로 반환하는 가짜 LLM. */
    private static final class CaptureLlm implements LlmClient {
        final List<List<LlmToolSpec>> seenTools = new ArrayList<>();
        private final List<LlmTurn> turns;
        private final AtomicInteger i = new AtomicInteger();

        CaptureLlm(List<LlmTurn> turns) {
            this.turns = turns;
        }

        @Override
        public LlmTurn next(List<LlmMessage> messages, List<LlmToolSpec> tools) {
            seenTools.add(tools);
            return turns.get(Math.min(i.getAndIncrement(), turns.size() - 1));
        }
    }

    private static ChatAgent agent(LlmClient llm) {
        return new ChatAgent(llm, SkillRegistry.FIXTURE_SKILL_QUERY);
    }

    private static LlmToolCall choiceRequest(Map<String, Object> args) {
        return new LlmToolCall("c1", "choice_request", args);
    }

    @Test
    void choice_request_툴이_LLM에_노출된다() {
        CaptureLlm llm = new CaptureLlm(List.of(new LlmTurn.Final("ok")));
        agent(llm).run(List.of(new HistoryMessage(Role.USER, "CVD-01 측정 분석해줘")), null);
        assertThat(llm.seenTools.get(0)).anyMatch(t -> t.name().equals("choice_request"));
    }

    @Test
    void choice_request_호출은_choiceRequests로_수집된다() {
        CaptureLlm llm = new CaptureLlm(List.of(
                new LlmTurn.ToolCalls(List.of(choiceRequest(Map.of(
                        "question", "어느 범위까지 점검할까요?",
                        "options", List.of(
                                Map.of("label", "온도만", "description", "TEMP_CH1 단일 채널"),
                                Map.of("label", "전체 챔버")),
                        "multiSelect", true)))),
                new LlmTurn.Final("선택해 주세요.")));
        AgentResult result = agent(llm).run(
                List.of(new HistoryMessage(Role.USER, "CVD-01 측정 분석해줘")), null);

        assertThat(result.choiceRequests()).hasSize(1);
        ChoiceRequest req = result.choiceRequests().get(0);
        assertThat(req.question()).isEqualTo("어느 범위까지 점검할까요?");
        assertThat(req.multiSelect()).isTrue();
        assertThat(req.options()).hasSize(2);
        ChoiceOption first = req.options().get(0);
        assertThat(first.label()).isEqualTo("온도만");
        assertThat(first.description()).isEqualTo("TEMP_CH1 단일 채널");
        assertThat(req.options().get(1).description()).isNull();
    }

    @Test
    void 문자열_옵션은_label로_정규화된다() {
        CaptureLlm llm = new CaptureLlm(List.of(
                new LlmTurn.ToolCalls(List.of(choiceRequest(Map.of(
                        "question", "어느 센서를 볼까요?",
                        "options", List.of("S-0004", "S-0005"))))),
                new LlmTurn.Final("선택해 주세요.")));
        AgentResult result = agent(llm).run(
                List.of(new HistoryMessage(Role.USER, "분석해줘")), null);

        assertThat(result.choiceRequests()).hasSize(1);
        List<ChoiceOption> options = result.choiceRequests().get(0).options();
        assertThat(options).extracting(ChoiceOption::label).containsExactly("S-0004", "S-0005");
        assertThat(options).allMatch(o -> o.description() == null);
    }

    @Test
    void 옵션이_2개_미만이면_수집되지_않는다() {
        CaptureLlm llm = new CaptureLlm(List.of(
                new LlmTurn.ToolCalls(List.of(choiceRequest(Map.of(
                        "question", "어느 센서를 볼까요?",
                        "options", List.of("S-0004"))))),
                new LlmTurn.Final("done")));
        AgentResult result = agent(llm).run(
                List.of(new HistoryMessage(Role.USER, "분석해줘")), null);
        assertThat(result.choiceRequests()).isEmpty();
    }

    @Test
    void 턴_내_같은_질문_재요청은_수집되지_않는다() {
        CaptureLlm llm = new CaptureLlm(List.of(
                new LlmTurn.ToolCalls(List.of(
                        choiceRequest(Map.of(
                                "question", "어느 센서를 볼까요?",
                                "options", List.of("S-0004", "S-0005"))),
                        choiceRequest(Map.of(
                                "question", "어느 센서를 볼까요?",
                                "options", List.of("S-0004", "S-0005"))))),
                new LlmTurn.Final("done")));
        AgentResult result = agent(llm).run(
                List.of(new HistoryMessage(Role.USER, "분석해줘")), null);
        assertThat(result.choiceRequests()).hasSize(1);
    }
}

package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.FinishReason;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.skills.SkillRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 스텝 한도 소진 반환 경로 — 스킬 풀이 비어 request_data 툴이 안 붙은 요청(#38 T12).
 * 그 요청에서 LLM 이 끝내 최종 답을 안 내면 dataRequests 가 null 인 채 소진 경로를
 * 타는데, 여기서 NPE 가 나던 확정 버그의 회귀 테스트다.
 */
class ChatOutOfStepsTest {

    /** 최종 답 없이 툴만 계속 부르는 가짜 LLM — 스텝 한도까지 몰고 간다. */
    private static final LlmClient NEVER_FINISHES = (messages, tools) ->
            new LlmTurn.ToolCalls(List.of(new LlmToolCall("c1", "없는_툴", Map.of())));

    @Test
    void 풀이_비어도_스텝_소진_응답이_NPE_없이_나온다() {
        ChatAgent agent = new ChatAgent(NEVER_FINISHES, SkillRegistry.FIXTURE_SKILL_QUERY, List::of);

        AgentResult result = agent.run(List.of(new HistoryMessage(Role.USER, "분석해줘")), null);

        assertThat(result.finishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(result.dataRequests()).isEmpty();
        assertThat(result.inputRequests()).isEmpty();
    }
}

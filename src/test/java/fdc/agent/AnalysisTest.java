package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.skills.SkillRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** fdc_trace_reading 호출 경로 — 툴이 부르면 그 표가 결과에 실리는가. */
class AnalysisTest {

    /** 받은 messages 를 포착하고, 지정 turn 을 순서대로 반환하는 가짜 LLM. */
    private static final class CaptureLlm implements LlmClient {
        final List<String> seen = new ArrayList<>();
        private final List<LlmTurn> turns;
        private final AtomicInteger i = new AtomicInteger();

        CaptureLlm(List<LlmTurn> turns) {
            this.turns = turns;
        }

        @Override
        public LlmTurn next(List<fdc.agent.llm.LlmTypes.LlmMessage> messages,
                List<fdc.agent.llm.LlmTypes.LlmToolSpec> tools) {
            seen.add(String.join("\n", messages.stream()
                    .map(m -> m.role().wire() + ":" + (m.content() != null ? m.content() : ""))
                    .toList()));
            return turns.get(Math.min(i.getAndIncrement(), turns.size() - 1));
        }
    }

    private static AgentResult run(LlmClient llm, String content) {
        ChatAgent agent = new ChatAgent(llm, SkillRegistry.FIXTURE_SKILL_QUERY);
        return agent.run(List.of(new HistoryMessage(Role.USER, content)));
    }

    @Test
    void LLM이_fdc_trace_reading을_부르면_측정_집계_표가_실린다() {
        CaptureLlm llm = new CaptureLlm(List.of(
                new LlmTurn.ToolCalls(List.of(new LlmToolCall("c1", "fdc_trace_reading", Map.of(
                        "equipment", "ETCH-01",
                        "param_index", "5",
                        "start", "2026-05-01",
                        "end", "2026-05-07")))),
                new LlmTurn.Final("분석 결과입니다.")));
        AgentResult result = run(llm, "분석");
        assertThat(result.tables().stream()
                .anyMatch(t -> t.title() != null && t.title().equals("reading_stats"))).isTrue();
    }
}

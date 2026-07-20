package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.FormContext;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.data.fixtures.FixtureRepo;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.skills.SkillRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Node 판 test/analysis.test.ts 포팅 — 폼 컨텍스트 주입(PARAM_INDEX)과
 * fdc_trace_reading 호출 경로.
 */
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
                    .map(m -> m.role() + ":" + (m.content() != null ? m.content() : ""))
                    .toList()));
            return turns.get(Math.min(i.getAndIncrement(), turns.size() - 1));
        }
    }

    private static AgentResult run(LlmClient llm, String content, FormContext formContext) {
        ChatAgent agent = new ChatAgent(llm, new FixtureRepo(), SkillRegistry.FIXTURE_SKILL_QUERY);
        return agent.run(List.of(new HistoryMessage("user", content)), formContext);
    }

    @Test
    void 폼_설비_PARAM_INDEX_기간을_프롬프트에_주입한다() {
        FormContext formContext = new FormContext(
                List.of(new FormContext.ContextRow("ETCH-01",
                        List.of(new FormContext.Chamber(List.of(new FormContext.Sensor("5")))))),
                new FormContext.TimeRange("2026-05-01", "2026-05-07"));
        CaptureLlm llm = new CaptureLlm(List.of(new LlmTurn.Final("ok")));
        run(llm, "분석해줘", formContext);

        String prompt = llm.seen.get(0);
        assertThat(prompt).contains("분석 대상");
        assertThat(prompt).contains("ETCH-01");
        assertThat(prompt).contains("PARAM_INDEX: 5");
        assertThat(prompt).contains("2026-05-01");
    }

    @Test
    void 빠진_항목은_미입력으로_표시한다() {
        FormContext formContext = new FormContext(
                List.of(new FormContext.ContextRow("ETCH-01", null)), null);
        CaptureLlm llm = new CaptureLlm(List.of(new LlmTurn.Final("ok")));
        run(llm, "분석", formContext);
        assertThat(llm.seen.get(0)).contains("PARAM_INDEX: (미입력)");
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
        AgentResult result = run(llm, "분석", null);
        assertThat(result.tables().stream()
                .anyMatch(t -> t.title() != null && t.title().contains("구간 측정 집계"))).isTrue();
    }
}

package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.QueryScope;
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
 * 질의 대상(스코프) 주입 — 사용자가 담은 설비·분석이 프롬프트 섹션으로 나가고,
 * 담긴 분석의 조회 키는 다시 카드로 요청되지 않는다.
 */
class ChatQueryScopeTest {

    private static final class CaptureLlm implements LlmClient {
        final List<String> seen = new ArrayList<>();
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
            return turns.get(Math.min(i.getAndIncrement(), turns.size() - 1));
        }
    }

    private static ChatAgent agent(LlmClient llm) {
        return new ChatAgent(llm, SkillRegistry.FIXTURE_SKILL_QUERY);
    }

    private static List<HistoryMessage> ask() {
        return List.of(new HistoryMessage(Role.USER, "최근 추세가 평소와 다른지 봐줘"));
    }

    @Test
    void 담긴_설비가_프롬프트에_나간다() {
        CaptureLlm llm = new CaptureLlm(List.of(new LlmTurn.Final("ok")));
        agent(llm).run(ask(), null, null, new QueryScope(List.of("CVD-01"), null));

        assertThat(llm.seen.get(0)).contains("[질의 대상").contains("설비 CVD-01 (전체)");
    }

    @Test
    void 담긴_분석은_스킬과_조회_키까지_적힌다() {
        // 같은 스킬이 두 설비에 걸릴 수 있으니, 어느 값이 어느 쪽 것인지는
        // 분석 줄에 붙는 조회 키로만 구분된다.
        CaptureLlm llm = new CaptureLlm(List.of(new LlmTurn.Final("ok")));
        agent(llm).run(ask(), null, null, new QueryScope(
                List.of("CVD-01"),
                List.of(new QueryScope.Analysis(
                        "a1", "CVD-02", "fdc_trace_reading", "측정 분포",
                        Map.of("days", "30")))));

        String prompt = llm.seen.get(0);
        assertThat(prompt).contains("설비 CVD-01 (전체)");
        assertThat(prompt).contains("설비 CVD-02 · 측정 분포 (fdc_trace_reading; days=30)");
    }

    @Test
    void 담긴_게_없으면_섹션을_안_넣는다() {
        // 스코프는 좁히는 장치이지 관문이 아니다 — 안 담았다고 답을 막지 않는다.
        CaptureLlm llm = new CaptureLlm(List.of(new LlmTurn.Final("ok")));
        agent(llm).run(ask(), null, null, new QueryScope(List.of(), List.of()));

        assertThat(llm.seen.get(0)).doesNotContain("[질의 대상");
    }

    @Test
    void 담긴_분석의_조회_키는_다시_카드로_요청되지_않는다() {
        // 진입 폼에서 사람이 이미 정한 값이라, 되물으면 같은 걸 두 번 묻는 셈이다.
        CaptureLlm llm = new CaptureLlm(List.of(
                new LlmTurn.ToolCalls(List.of(new LlmToolCall("c1", "request_input", Map.of(
                        "skill", "fdc_trace_reading", "key", "days", "label", "DAYS")))),
                new LlmTurn.Final("분석을 이어갑니다.")));

        AgentResult result = agent(llm).run(ask(), null, null, new QueryScope(
                null,
                List.of(new QueryScope.Analysis(
                        "a1", "CVD-01", "fdc_trace_reading", "측정 분포",
                        Map.of("days", "30")))));

        assertThat(result.inputRequests()).isEmpty();
    }

    @Test
    void 스코프가_없으면_지금까지와_같다() {
        CaptureLlm llm = new CaptureLlm(List.of(new LlmTurn.Final("ok")));
        agent(llm).run(ask(), null);
        assertThat(llm.seen.get(0)).doesNotContain("[질의 대상");
    }
}

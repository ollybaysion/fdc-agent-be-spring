package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.data.fixtures.FixtureRepo;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.skills.SkillRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * recommendQuestion(추천 후속 질문) 파싱·상한·실패 폴백을 fake LLM 으로
 * 결정적으로 확인한다.
 */
class ChatFollowupsTest {

    /** 후속 질문 요청 턴인지 — 마지막 user 메시지 내용으로 판별. */
    private static boolean isFollowupPrompt(List<LlmMessage> messages) {
        LlmMessage last = messages.get(messages.size() - 1);
        return last.content() != null && last.content().contains("후속 질문");
    }

    /** 첫 턴은 최종 답, 후속 질문 턴은 지정 텍스트를 돌려주는 fake. */
    private static LlmClient fakeLlm(String followupContent) {
        return (messages, tools) -> isFollowupPrompt(messages)
                ? new LlmTurn.Final(followupContent)
                : new LlmTurn.Final("분석 결과입니다.");
    }

    private static AgentResult run(LlmClient llm, String content) {
        ChatAgent agent = new ChatAgent(llm, new FixtureRepo(), SkillRegistry.FIXTURE_SKILL_QUERY);
        return agent.run(List.of(new HistoryMessage("user", content)), null);
    }

    @Test
    void 후속_질문_JSON_배열을_파싱해_최대_3개까지_순서대로_반환() {
        AgentResult r = run(fakeLlm("추천: [\"추세는?\", \"이상치 원인은?\", \"동종 비교는?\", \"네번째\"]"), "안녕하세요");
        assertThat(r.text()).isEqualTo("분석 결과입니다.");
        assertThat(r.recommendQuestion()).containsExactly("추세는?", "이상치 원인은?", "동종 비교는?");
    }

    @Test
    void 문자열이_아닌_항목은_걸러낸다() {
        AgentResult r = run(fakeLlm("[\"a\", 2, null, \"b\"]"), "안녕");
        assertThat(r.recommendQuestion()).containsExactly("a", "b");
    }

    @Test
    void JSON_배열이_없으면_빈_배열_답변_자체는_영향_없음() {
        AgentResult r = run(fakeLlm("죄송하지만 제안할 후속 질문이 없습니다."), "안녕");
        assertThat(r.text()).isEqualTo("분석 결과입니다.");
        assertThat(r.recommendQuestion()).isEmpty();
    }
}

package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.Role;
import fdc.agent.data.fixtures.FixtureRepo;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmToolSpec;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.skills.SkillRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Design B(이슈 #11) — 붙여넣은 스냅샷은 프롬프트에 스키마만 주입되고, 행은 임시
 * SQLite 로 적재돼 query_snapshot 툴로 조회된다. 스크립트된 LLM 으로 프롬프트·툴 노출·
 * 조회 왕복을 정밀 검증한다.
 */
class ChatSnapshotTest {

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
        return new ChatAgent(llm, new FixtureRepo(), SkillRegistry.FIXTURE_SKILL_QUERY);
    }

    private static ChatDataSnapshot rowsSnap() {
        return new ChatDataSnapshot("sensor_list", "챔버별 센서", "2026-07-22T00:00",
                List.of("CHAMBER", "SENSOR"), 2,
                List.of(Arrays.asList("챔버A", "온도"), Arrays.asList(null, "압력")));
    }

    @Test
    void 행_있는_스냅샷은_스키마만_주입하고_행은_프롬프트에_넣지_않는다() {
        CaptureLlm llm = new CaptureLlm(List.of(new LlmTurn.Final("ok")));
        agent(llm).run(List.of(new HistoryMessage(Role.USER, "이 데이터로 분석")), null, List.of(rowsSnap()));

        String prompt = llm.seen.get(0);
        // 스키마(테이블·컬럼)와 query_snapshot 안내는 주입된다.
        assertThat(prompt).contains("sensor_list").contains("CHAMBER").contains("SENSOR");
        assertThat(prompt).contains("query_snapshot");
        // 행 셀 값은 프롬프트에 없다(대체 = 스키마만).
        assertThat(prompt).doesNotContain("온도");
        assertThat(prompt).doesNotContain("압력");
        // query_snapshot 툴이 LLM 에 노출된다.
        assertThat(llm.seenTools.get(0)).anyMatch(t -> t.name().equals("query_snapshot"));
    }

    @Test
    void query_snapshot_호출은_적재된_행을_표로_돌려준다() {
        CaptureLlm llm = new CaptureLlm(List.of(
                new LlmTurn.ToolCalls(List.of(new LlmToolCall("c1", "query_snapshot",
                        Map.of("sql", "SELECT * FROM \"sensor_list\"")))),
                new LlmTurn.Final("조회 완료")));
        AgentResult result = agent(llm).run(
                List.of(new HistoryMessage(Role.USER, "이 데이터 조회")), null, List.of(rowsSnap()));

        assertThat(result.tables()).anyMatch(t -> t.rows().stream()
                .anyMatch(r -> "온도".equals(r.get("SENSOR"))));
    }

    @Test
    void 행_없는_스냅샷은_임시DB없이_내용_미첨부로_알린다() {
        ChatDataSnapshot catalog = new ChatDataSnapshot("recipe", "레시피 STEP", "2026-07-22T00:00",
                List.of("STEP_NO"), 5, null);
        CaptureLlm llm = new CaptureLlm(List.of(new LlmTurn.Final("ok")));
        agent(llm).run(List.of(new HistoryMessage(Role.USER, "분석")), null, List.of(catalog));

        String prompt = llm.seen.get(0);
        assertThat(prompt).contains("레시피 STEP").contains("내용 미첨부");
        // 담을 행이 없으니 query_snapshot 툴도 노출되지 않는다.
        assertThat(llm.seenTools.get(0)).noneMatch(t -> t.name().equals("query_snapshot"));
    }

    @Test
    void SELECT_외_query_snapshot은_결과표없이_사유를_되먹인다() {
        CaptureLlm llm = new CaptureLlm(List.of(
                new LlmTurn.ToolCalls(List.of(new LlmToolCall("c1", "query_snapshot",
                        Map.of("sql", "DROP TABLE \"sensor_list\"")))),
                new LlmTurn.Final("done")));
        AgentResult result = agent(llm).run(
                List.of(new HistoryMessage(Role.USER, "조회")), null, List.of(rowsSnap()));
        // 가드가 막아 결과 표는 실리지 않는다(사유가 LLM 에 되먹여진다).
        assertThat(result.tables()).isEmpty();
    }
}

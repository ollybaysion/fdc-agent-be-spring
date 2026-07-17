package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.AgentTool;
import fdc.agent.chat.AgentTool.ToolResult;
import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.data.fixtures.FixtureRepo;
import fdc.agent.llm.MockLlm;
import fdc.agent.skills.SkillLoader;
import fdc.agent.skills.SkillQuery;
import fdc.agent.skills.SkillRegistry;
import fdc.agent.skills.SkillSpec;
import fdc.agent.skills.SkillWiring;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Node 판 test/skill-loader.test.ts 포팅 — spec.json → 에이전트 툴(하이브리드 C). */
class SkillLoaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static <T> T readJson(String name, Class<T> type) {
        try (var in = SkillLoaderTest.class.getClassLoader()
                .getResourceAsStream("skills/" + name)) {
            return JSON.readValue(in, type);
        } catch (Exception e) {
            throw new IllegalStateException("스킬 파일 로드 실패: " + name, e);
        }
    }

    private static final SkillSpec SPEC = readJson("fdc-explain-sensor.spec.json", SkillSpec.class);
    private static final SkillWiring WIRING = readJson("fdc-explain-sensor.wiring.json", SkillWiring.class);

    @Test
    @SuppressWarnings("unchecked")
    void spec을_툴로_컴파일한다_이름_snake_snsr_id_필수() {
        SkillQuery query = (sql, binds) -> List.of();
        AgentTool tool = SkillLoader.loadSkill(SPEC, query, WIRING);
        assertThat(tool.name()).isEqualTo("fdc_explain_sensor");
        List<String> required = (List<String>) tool.parameters().get("required");
        assertThat(required).contains("snsr_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 다중_인자_스킬은_parameters에_여러_프로퍼티_각_인자를_bind로() {
        SkillSpec spec = new SkillSpec(
                "x-multi", "{a} {b}", "설명", null, null,
                List.of(new SkillSpec.SkillStep("s", null, "SELECT 1 FROM t WHERE x = :x AND y = :y", null)),
                null, null, null);
        SkillWiring wiring = new SkillWiring(
                List.of(new SkillWiring.SkillArg("a", null), new SkillWiring.SkillArg("b", null)),
                Map.of(0, Map.of(
                        "x", new SkillWiring.BindSource("arg", "a", null, null),
                        "y", new SkillWiring.BindSource("arg", "b", null, null))));
        List<Map<String, Object>> calls = new ArrayList<>();
        SkillQuery query = (sql, binds) -> {
            calls.add(new LinkedHashMap<>(binds));
            return List.of(Map.of("OK", 1));
        };
        AgentTool tool = SkillLoader.loadSkill(spec, query, wiring);

        List<String> required = (List<String>) tool.parameters().get("required");
        assertThat(required).containsExactly("a", "b");
        Map<String, Object> props = (Map<String, Object>) tool.parameters().get("properties");
        assertThat(props.keySet()).containsExactly("a", "b");

        tool.execute().run(Map.of("a", "A1", "b", "B2"));
        assertThat(calls.get(0)).isEqualTo(Map.of("x", "A1", "y", "B2"));
    }

    @Test
    void 스텝을_순서대로_실행하고_bind를_배선한다() {
        record Call(String sql, Map<String, Object> binds) {
        }
        List<Call> calls = new ArrayList<>();
        SkillQuery query = (sql, binds) -> {
            calls.add(new Call(sql, new LinkedHashMap<>(binds)));
            String s = sql.toLowerCase();
            if (s.contains("fdc_sensor")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("SNSR_ID", "S-0004");
                row.put("EQP_ID", "CVD-01");
                row.put("SNSR_TYPE_CD", "FLOW");
                row.put("UNIT_CD", "SCCM");
                row.put("USE_YN", "N");
                return List.of(row);
            }
            if (s.contains("fdc_equipment")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("EQP_ID", "CVD-01");
                row.put("EQP_NAME", "증착기 1호");
                row.put("MODEL_CD", "CV-800");
                row.put("VENDOR", "AMAT");
                row.put("USE_YN", "N");
                return List.of(row);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("D", "2026-05-11");
            row.put("EVT_TYPE_CD", "O");
            row.put("EVT_LABEL", "점검");
            return List.of(row);
        };

        AgentTool tool = SkillLoader.loadSkill(SPEC, query, WIRING);
        ToolResult res = tool.execute().run(Map.of("snsr_id", "S-0004"));

        assertThat(calls).hasSize(3);
        assertThat(calls.get(0).binds()).isEqualTo(Map.of("id", "S-0004")); // 인자 → step0 :id
        assertThat(calls.get(1).binds()).isEqualTo(Map.of("eqp", "CVD-01")); // step0.EQP_ID → step1 :eqp
        assertThat(calls.get(2).binds()).isEqualTo(Map.of("eqp", "CVD-01"));

        // 조회 사실 + 출력 형식 프로즈가 LLM 서술용으로 실림.
        assertThat(res.summary()).contains("S-0004");
        assertThat(res.summary()).contains("출력 형식");
        assertThat(res.tables()).hasSize(3);
    }

    @Test
    void 첫_스텝이_0행이면_후속_스텝은_bind_소스가_없어_스킵된다() {
        List<String> calls = new ArrayList<>();
        SkillQuery query = (sql, binds) -> {
            calls.add(sql);
            return List.of(); // 항상 0행
        };
        AgentTool tool = SkillLoader.loadSkill(SPEC, query, WIRING);
        tool.execute().run(Map.of("snsr_id", "S-9999"));
        // step0 만 실행 — step1/2 는 EQP_ID 부재로 query 호출조차 안 함.
        assertThat(calls).hasSize(1);
    }

    @Test
    void 에이전트와_스킬_통합_센서_질문이면_스킬_툴_호출과_조회_데이터가_응답에_실린다() {
        ChatAgent agent = new ChatAgent(
                new MockLlm(), new FixtureRepo(), SkillRegistry.FIXTURE_SKILL_QUERY);
        AgentResult result = agent.run(
                List.of(new HistoryMessage("user", "S-0004 센서 설명해줘")), null);
        assertThat(result.text()).contains("S-0004");
        // 3스텝 표(센서/설비/이벤트).
        List<String> titles = result.tables().stream()
                .map(t -> t.title() != null ? t.title() : "")
                .toList();
        assertThat(titles.stream().anyMatch(t -> t.contains("센서"))).isTrue();
        assertThat(titles.stream().anyMatch(t -> t.contains("설비"))).isTrue();
    }
}

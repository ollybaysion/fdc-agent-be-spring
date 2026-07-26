package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** spec.json → 에이전트 툴(하이브리드 C). */
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

    @Test
    @SuppressWarnings("unchecked")
    void spec을_툴로_컴파일한다_이름_snake_snsr_id_필수() {
        SkillQuery query = (sql, binds) -> List.of();
        AgentTool tool = SkillLoader.loadSkill(SPEC, query);
        assertThat(tool.name()).isEqualTo("fdc_explain_sensor");
        List<String> required = (List<String>) tool.parameters().get("required");
        assertThat(required).contains("snsr_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 다중_인자_스킬의_parameters는_spec_inputs가_소유한다() {
        SkillSpec spec = new SkillSpec(
                "x-multi",
                "{a} {b}",
                new SkillSpec.SkillScope("센서", "단일", "상태"),
                "현재 상태",
                null,
                "도메인 주의사항.",
                List.of(
                        new SkillSpec.SkillInput("a", true, "첫 인자"),
                        new SkillSpec.SkillInput("b", true, "둘째 인자")),
                List.of(new SkillSpec.SkillDependency("agent-db-plugin", null, null)),
                List.of(new SkillSpec.SkillStep(
                        "s", "현재 상태", null, "SELECT 1 FROM t WHERE x = :x AND y = :y",
                        Map.of(
                                "x", new SkillSpec.BindSource("arg", "a", null, null),
                                "y", new SkillSpec.BindSource("arg", "b", null, null)),
                        null, null)),
                new SkillSpec.SkillOutput(
                        List.of(
                                "없는 사유를 추측한다 — 사유 컬럼은 데이터에 없다",
                                "측정값을 지어낸다 — 이 스킬 범위 밖이다",
                                "코드를 구체화한다 — 라벨 이상은 모른다"),
                        List.of(
                                new SkillSpec.SkillExample("전체 설명", "넓은 답이다."),
                                new SkillSpec.SkillExample("좁은 질문", "좁은 답이다."))),
                null);
        List<Map<String, Object>> calls = new ArrayList<>();
        SkillQuery query = (sql, binds) -> {
            calls.add(new LinkedHashMap<>(binds));
            return List.of(Map.of("OK", 1));
        };
        AgentTool tool = SkillLoader.loadSkill(spec, query);

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

        AgentTool tool = SkillLoader.loadSkill(SPEC, query);
        ToolResult res = tool.execute().run(Map.of("snsr_id", "S-0004"));

        assertThat(calls).hasSize(3);
        assertThat(calls.get(0).binds()).isEqualTo(Map.of("id", "S-0004")); // 인자 → step0 :id
        assertThat(calls.get(1).binds()).isEqualTo(Map.of("eqp", "CVD-01")); // step0.EQP_ID → step1 :eqp
        assertThat(calls.get(2).binds()).isEqualTo(Map.of("eqp", "CVD-01"));

        // 조회 사실 + 출력 지침(v2)이 LLM 서술용으로 실림.
        assertThat(res.summary()).contains("S-0004");
        assertThat(res.summary()).contains("[출력 지침]");
        // 완결성 바닥은 steps[].produces 에서 합성 — 조회한 차원이 빠지지 않게.
        assertThat(res.summary())
                .contains("반드시 포함 (질문이 특정 항목만 묻는 게 아니면): 센서 정체·상태 · 소속 설비 · 최근 설비 이벤트");
        assertThat(res.summary()).contains("[하지 말 것]");
        assertThat(res.summary()).contains("사유 컬럼은 데이터에 없다");
        assertThat(res.tables()).hasSize(3);
    }

    @Test
    void description은_spec_필드가_아니라_scope_focus_inputs에서_합성된다() {
        SkillQuery query = (sql, binds) -> List.of();
        AgentTool tool = SkillLoader.loadSkill(SPEC, query);
        assertThat(tool.description())
                .isEqualTo("특정 센서의 정체·소속 설비·현재 상태를 묻는 상황에서 호출한다 (snsr_id 필요).");

        // 생성 이력은 다른 골격 + 다른 조사.
        SkillSpec trace = readJson("fdc-trace-reading.spec.json", SkillSpec.class);
        assertThat(SkillLoader.synthesizeDescription(trace))
                .isEqualTo("특정 센서 측정값이 어떻게 만들어졌는지 묻는 상황에서 호출한다 "
                        + "(equipment·param_index·start·end 필요).");
    }

    @Test
    void 첫_스텝이_0행이면_후속_스텝은_bind_소스가_없어_스킵된다() {
        List<String> calls = new ArrayList<>();
        SkillQuery query = (sql, binds) -> {
            calls.add(sql);
            return List.of(); // 항상 0행
        };
        AgentTool tool = SkillLoader.loadSkill(SPEC, query);
        tool.execute().run(Map.of("snsr_id", "S-9999"));
        // step0 만 실행 — step1/2 는 EQP_ID 부재로 query 호출조차 안 함.
        assertThat(calls).hasSize(1);
    }

    // ── 로드 시 배선 검증 — 어긋난 spec 은 런타임 침묵 스킵이 아니라 기동 실패다 ──

    /** 검증 테스트용 최소 spec — steps 만 바꿔 끼운다(입력은 a 하나). */
    private static SkillSpec specOf(List<SkillSpec.SkillStep> steps) {
        return new SkillSpec(
                "x-wire",
                "{a}",
                new SkillSpec.SkillScope("센서", "단일", "상태"),
                "현재 상태",
                null,
                "도메인 주의사항.",
                List.of(new SkillSpec.SkillInput("a", true, "조회 키")),
                List.of(new SkillSpec.SkillDependency("agent-db-plugin", null, null)),
                steps,
                new SkillSpec.SkillOutput(
                        List.of(
                                "없는 사유를 추측한다 — 사유 컬럼은 데이터에 없다",
                                "측정값을 지어낸다 — 이 스킬 범위 밖이다",
                                "코드를 구체화한다 — 라벨 이상은 모른다"),
                        List.of(
                                new SkillSpec.SkillExample("전체 설명", "넓은 답이다."),
                                new SkillSpec.SkillExample("좁은 질문", "좁은 답이다."))),
                null);
    }

    private static final SkillQuery NOOP_QUERY = (sql, binds) -> List.of();

    @Test
    void 로드_검증_sql의_bind_변수를_binds가_선언하지_않으면_기동_실패() {
        SkillSpec spec = specOf(List.of(new SkillSpec.SkillStep(
                "s", "현재 상태", null, "SELECT 1 FROM t WHERE x = :x AND y = :y",
                Map.of("x", new SkillSpec.BindSource("arg", "a", null, null)), null, null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sql uses :y");
    }

    @Test
    void 로드_검증_sql에_없는_bind를_선언하면_기동_실패() {
        SkillSpec spec = specOf(List.of(new SkillSpec.SkillStep(
                "s", "현재 상태", null, "SELECT 1 FROM t WHERE x = :x",
                Map.of(
                        "x", new SkillSpec.BindSource("arg", "a", null, null),
                        "ghost", new SkillSpec.BindSource("arg", "a", null, null)),
                null, null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sql has no :ghost");
    }

    @Test
    void 로드_검증_inputs에_없는_인자를_참조하면_기동_실패() {
        SkillSpec spec = specOf(List.of(new SkillSpec.SkillStep(
                "s", "현재 상태", null, "SELECT 1 FROM t WHERE x = :x",
                Map.of("x", new SkillSpec.BindSource("arg", "nope", null, null)), null, null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no input named \"nope\"");
    }

    @Test
    void 로드_검증_step_참조는_앞_스텝만_허용() {
        // 자기 자신(스텝 0이 스텝 0을) — 미래 참조와 같은 거부.
        SkillSpec spec = specOf(List.of(new SkillSpec.SkillStep(
                "s", "현재 상태", null, "SELECT 1 FROM t WHERE x = :x",
                Map.of("x", new SkillSpec.BindSource("step", null, 0, "C")), null, null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an earlier step");
    }

    @Test
    void 로드_검증_따옴표_리터럴_속_콜론은_bind가_아니다() {
        // 날짜 마스크 'HH24:MI' 의 ':MI' 가 bind 로 오탐되면 안 된다.
        SkillSpec spec = specOf(List.of(new SkillSpec.SkillStep(
                "s", "현재 상태", null, "SELECT TO_CHAR(t, 'HH24:MI') FROM d WHERE x = :x",
                Map.of("x", new SkillSpec.BindSource("arg", "a", null, null)), null, null)));
        assertThat(SkillLoader.loadSkill(spec, NOOP_QUERY).name()).isEqualTo("x_wire");
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

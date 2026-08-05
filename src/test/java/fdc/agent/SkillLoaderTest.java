package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.AgentTool;
import fdc.agent.chat.AgentTool.ToolResult;
import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.Role;
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

/** spec.json(v3) → 에이전트 툴(하이브리드 C). */
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

    private static final SkillQuery NOOP_QUERY = (sql, binds) -> List.of();

    @Test
    @SuppressWarnings("unchecked")
    void spec을_툴로_컴파일한다_이름_snake_snsr_id_필수() {
        AgentTool tool = SkillLoader.loadSkill(SPEC, NOOP_QUERY);
        assertThat(tool.name()).isEqualTo("fdc_explain_sensor");
        List<String> required = (List<String>) tool.parameters().get("required");
        assertThat(required).contains("snsr_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 다중_인자_스킬의_parameters는_spec_inputs가_소유한다() {
        SkillSpec spec = new SkillSpec("x-multi", "{a} {b}",
                List.of("a 랑 b 로 뭐 나와?"), "a 와 b 로 특정되는 행이 있는지.",
                null,
                List.of(
                        new SkillSpec.SkillInput("a", true, "첫 인자"),
                        new SkillSpec.SkillInput("b", true, "둘째 인자")),
                List.of(new SkillSpec.SkillDependency("agent-db-plugin", null, null)),
                List.of(new SkillSpec.SkillNeed("hit", "그 행이 있는지", null,
                        List.of(new SkillSpec.Fill("row", "OK")))),
                List.of(new SkillSpec.SpecQuery("row", "sql", "t",
                        "SELECT OK FROM t WHERE x = :x AND y = :y",
                        Map.of(
                                "x", new SkillSpec.BindSource("arg", "a", null, null),
                                "y", new SkillSpec.BindSource("arg", "b", null, null)),
                        null)),
                output(), null);

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

        tool.run(Map.of("a", "A1", "b", "B2"));
        assertThat(calls.get(0)).isEqualTo(Map.of("x", "A1", "y", "B2"));
    }

    @Test
    void 필요한_조달만_돌고_bind를_배선한다() {
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
        ToolResult res = tool.run(Map.of("snsr_id", "S-0004"));

        assertThat(calls).hasSize(3);
        assertThat(calls.get(0).binds()).isEqualTo(Map.of("id", "S-0004")); // 인자 → :id
        assertThat(calls.get(1).binds()).isEqualTo(Map.of("eqp", "CVD-01")); // sensor_row.EQP_ID
        assertThat(calls.get(2).binds()).isEqualTo(Map.of("eqp", "CVD-01"));

        // 조회 사실 + 판정 + 출력 지침이 LLM 서술용으로 실림.
        assertThat(res.summary()).contains("S-0004");
        assertThat(res.summary()).contains("[출력 지침]");
        // 완결성 바닥은 조회 산출물이 아니라 채워진 need 에서 나온다.
        assertThat(res.summary()).contains("[알아낸 것]").contains("- 무엇을 재는 센서인지");
        assertThat(res.summary()).contains("반드시 포함 (질문이 특정 항목만 묻는 게 아니면): 무엇을 재는 센서인지");
        assertThat(res.summary()).contains("[하지 말 것]");
        assertThat(res.summary()).contains("사유 컬럼은 데이터에 없다");
        assertThat(res.tables()).hasSize(3);
    }

    @Test
    void 게이트가_닫힌_need_의_조달은_아예_돌지_않는다() {
        // 갈림형 분기의 실물 — 물리로 판명되면 가상 쪽 조회는 시도조차 하지 않는다.
        // 그래야 안 돈 조회가 "0행(= 없다)"으로 오해되지 않는다.
        SkillSpec gated = new SkillSpec("x-gate", "{a}",
                List.of("a 어디서 오는 거야?"), "a 가 물리인지 가상인지, 가상이면 수식이 무엇인지.",
                null,
                List.of(new SkillSpec.SkillInput("a", true, "조회 키")),
                List.of(new SkillSpec.SkillDependency("agent-db-plugin", null, null)),
                List.of(
                        new SkillSpec.SkillNeed("kind", "물리인지 가상인지", null,
                                List.of(new SkillSpec.Fill("base", "KIND"))),
                        new SkillSpec.SkillNeed("formula", "값을 만드는 수식", "kind = VIRTUAL",
                                List.of(new SkillSpec.Fill("formula_row", "EXPR")))),
                List.of(
                        new SkillSpec.SpecQuery("base", "sql", "t",
                                "SELECT KIND FROM t WHERE id = :a",
                                Map.of("a", new SkillSpec.BindSource("arg", "a", null, null)), null),
                        new SkillSpec.SpecQuery("formula_row", "sql", "t2",
                                "SELECT EXPR FROM t2 WHERE id = :a",
                                Map.of("a", new SkillSpec.BindSource("arg", "a", null, null)), null)),
                output(), null);

        List<String> physical = new ArrayList<>();
        SkillLoader.loadSkill(gated, (sql, binds) -> {
            physical.add(sql);
            return sql.contains("KIND") ? List.of(Map.of("KIND", "PHYSICAL")) : List.of();
        }).run(Map.of("a", "X-1"));
        assertThat(physical).hasSize(1);

        List<String> virtual = new ArrayList<>();
        SkillLoader.loadSkill(gated, (sql, binds) -> {
            virtual.add(sql);
            return sql.contains("KIND")
                    ? List.of(Map.of("KIND", "VIRTUAL"))
                    : List.of(Map.of("EXPR", "(A + B) / 2"));
        }).run(Map.of("a", "X-1"));
        assertThat(virtual).hasSize(2);
    }

    @Test
    void description은_spec_필드가_아니라_questions_인용으로_합성된다() {
        AgentTool tool = SkillLoader.loadSkill(SPEC, NOOP_QUERY);
        assertThat(tool.description())
                .isEqualTo("\"S-0004 설명해줘\", \"S-0004 어느 설비 거야?\", \"S-0004 지금 쓰는 센서야?\""
                        + " 같은 질문에 답한다 (snsr_id 필요).");

        SkillSpec trace = readJson("fdc-trace-reading.spec.json", SkillSpec.class);
        assertThat(SkillLoader.synthesizeDescription(trace))
                .endsWith("같은 질문에 답한다 (equipment·param_index·start·end 필요).");
    }

    @Test
    void 첫_조달이_0행이면_후속_조달은_돌지_않고_답불가로_끝난다() {
        List<String> calls = new ArrayList<>();
        SkillQuery query = (sql, binds) -> {
            calls.add(sql);
            return List.of(); // 항상 0행
        };
        AgentTool tool = SkillLoader.loadSkill(SPEC, query);
        ToolResult res = tool.run(Map.of("snsr_id", "S-9999"));

        // sensor_row 만 실행 — 나머지는 EQP_ID 부재로 query 호출조차 안 한다.
        assertThat(calls).hasSize(1);
        assertThat(res.summary()).contains("[확인되지 않은 것");
        // 안 돈 조회를 "없다"로 읽지 않게, 없음의 종류를 갈라 적는다.
        assertThat(res.summary()).contains("sensor_row: (조회 결과 0행)");
        assertThat(res.summary()).contains("equipment_row: (앞 조달 결과가 없어 조회하지 못함");
    }

    // ── 로드 시 검증 — 어긋난 spec 은 런타임 침묵 스킵이 아니라 기동 실패다 ──

    private static SkillSpec.SkillOutput output() {
        return new SkillSpec.SkillOutput(
                List.of(
                        "없는 사유를 추측한다 — 사유 컬럼은 데이터에 없다",
                        "측정값을 지어낸다 — 이 스킬 범위 밖이다",
                        "코드를 구체화한다 — 라벨 이상은 모른다"),
                List.of(
                        new SkillSpec.SkillExample("전체 설명", "넓은 답이다."),
                        new SkillSpec.SkillExample("좁은 질문", "좁은 답이다.")));
    }

    /** 검증 테스트용 최소 spec — needs·queries 만 바꿔 끼운다(입력은 a 하나). */
    private static SkillSpec specOf(
            List<SkillSpec.SkillNeed> needs, List<SkillSpec.SpecQuery> queries) {
        return new SkillSpec("x-wire", "{a}",
                List.of("a 뭐야?"), "a 로 특정되는 것의 상태.", null,
                List.of(new SkillSpec.SkillInput("a", true, "조회 키")),
                List.of(new SkillSpec.SkillDependency("agent-db-plugin", null, null)),
                needs, queries, output(), null);
    }

    private static List<SkillSpec.SkillNeed> oneNeed(String query, String column) {
        return List.of(new SkillSpec.SkillNeed("n", "그 값", null,
                List.of(new SkillSpec.Fill(query, column))));
    }

    @Test
    void 로드_검증_sql의_bind_변수를_binds가_선언하지_않으면_기동_실패() {
        SkillSpec spec = specOf(oneNeed("s", "OK"), List.of(new SkillSpec.SpecQuery(
                "s", "sql", "t", "SELECT OK FROM t WHERE x = :x AND y = :y",
                Map.of("x", new SkillSpec.BindSource("arg", "a", null, null)), null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sql uses :y");
    }

    @Test
    void 로드_검증_sql에_없는_bind를_선언하면_기동_실패() {
        SkillSpec spec = specOf(oneNeed("s", "OK"), List.of(new SkillSpec.SpecQuery(
                "s", "sql", "t", "SELECT OK FROM t WHERE x = :x",
                Map.of(
                        "x", new SkillSpec.BindSource("arg", "a", null, null),
                        "ghost", new SkillSpec.BindSource("arg", "a", null, null)),
                null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sql has no :ghost");
    }

    @Test
    void 로드_검증_inputs에_없는_인자를_참조하면_기동_실패() {
        SkillSpec spec = specOf(oneNeed("s", "OK"), List.of(new SkillSpec.SpecQuery(
                "s", "sql", "t", "SELECT OK FROM t WHERE x = :x",
                Map.of("x", new SkillSpec.BindSource("arg", "nope", null, null)), null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no input named \"nope\"");
    }

    @Test
    void 로드_검증_없는_조달을_바인드로_참조하면_기동_실패() {
        SkillSpec spec = specOf(oneNeed("s", "OK"), List.of(new SkillSpec.SpecQuery(
                "s", "sql", "t", "SELECT OK FROM t WHERE x = :x",
                Map.of("x", new SkillSpec.BindSource("query", null, "nope", "C")), null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no query named \"nope\"");
    }

    @Test
    void 로드_검증_조달_배선이_순환하면_기동_실패() {
        // 순서가 사라진 카탈로그에서 "앞 스텝만" 규칙을 대신하는 것이 순환 금지다.
        SkillSpec spec = specOf(oneNeed("p", "OK"), List.of(
                new SkillSpec.SpecQuery("p", "sql", "t", "SELECT OK FROM t WHERE x = :x",
                        Map.of("x", new SkillSpec.BindSource("query", null, "q", "C")), null),
                new SkillSpec.SpecQuery("q", "sql", "t", "SELECT C FROM t WHERE x = :x",
                        Map.of("x", new SkillSpec.BindSource("query", null, "p", "OK")), null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("순환");
    }

    @Test
    void 로드_검증_filledBy_컬럼이_SELECT_목록에_없으면_기동_실패() {
        // 컬럼까지 못 박기로 한 결정의 안전망 — SQL 만 고치고 spec 을 안 고친 사고를 잡는다.
        SkillSpec spec = specOf(oneNeed("s", "GHOST"), List.of(new SkillSpec.SpecQuery(
                "s", "sql", "t", "SELECT OK FROM t WHERE x = :x",
                Map.of("x", new SkillSpec.BindSource("arg", "a", null, null)), null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SELECT 목록에 GHOST 이 없습니다");
    }

    @Test
    void 로드_검증_when이_없는_need를_가리키면_기동_실패() {
        SkillSpec spec = specOf(
                List.of(new SkillSpec.SkillNeed("n", "그 값", "nope = X",
                        List.of(new SkillSpec.Fill("s", "OK")))),
                List.of(new SkillSpec.SpecQuery("s", "sql", "t", "SELECT OK FROM t WHERE x = :x",
                        Map.of("x", new SkillSpec.BindSource("arg", "a", null, null)), null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no need named \"nope\"");
    }

    @Test
    void 로드_검증_when이_순환하면_기동_실패() {
        SkillSpec spec = specOf(
                List.of(
                        new SkillSpec.SkillNeed("n1", "값 1", "n2 = X",
                                List.of(new SkillSpec.Fill("s", "OK"))),
                        new SkillSpec.SkillNeed("n2", "값 2", "n1 = X",
                                List.of(new SkillSpec.Fill("s", "OK")))),
                List.of(new SkillSpec.SpecQuery("s", "sql", "t", "SELECT OK FROM t WHERE x = :x",
                        Map.of("x", new SkillSpec.BindSource("arg", "a", null, null)), null)));
        assertThatThrownBy(() -> SkillLoader.loadSkill(spec, NOOP_QUERY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("when 이 순환합니다");
    }

    @Test
    void 로드_검증_따옴표_리터럴_속_콜론은_bind가_아니다() {
        // 날짜 마스크 'HH24:MI' 의 ':MI' 가 bind 로 오탐되면 안 된다.
        SkillSpec spec = specOf(oneNeed("s", "T"), List.of(new SkillSpec.SpecQuery(
                "s", "sql", "d", "SELECT TO_CHAR(t, 'HH24:MI') AS T FROM d WHERE x = :x",
                Map.of("x", new SkillSpec.BindSource("arg", "a", null, null)), null)));
        assertThat(SkillLoader.loadSkill(spec, NOOP_QUERY).name()).isEqualTo("x_wire");
    }

    @Test
    void 에이전트와_스킬_통합_센서_질문이면_스킬_툴_호출과_조회_데이터가_응답에_실린다() {
        ChatAgent agent = new ChatAgent(
                new MockLlm(), SkillRegistry.FIXTURE_SKILL_QUERY);
        AgentResult result = agent.run(
                List.of(new HistoryMessage(Role.USER, "S-0004 센서 설명해줘")), null);
        assertThat(result.text()).contains("S-0004");
        List<String> titles = result.tables().stream()
                .map(t -> t.title() != null ? t.title() : "")
                .toList();
        assertThat(titles).contains("sensor_row", "equipment_row");
    }
}

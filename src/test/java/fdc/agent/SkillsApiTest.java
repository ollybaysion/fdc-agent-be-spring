package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/fdc/v1/skills — 사람이 고를 스킬 목록 계약. */
@SpringBootTest
@AutoConfigureMockMvc
class SkillsApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    private JsonNode skills() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/fdc/v1/skills")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return JSON.readTree(res.getContentAsString(StandardCharsets.UTF_8)).path("skills");
    }

    private static JsonNode byName(JsonNode skills, String name) {
        for (JsonNode s : skills) {
            if (name.equals(s.path("name").asText())) {
                return s;
            }
        }
        return null;
    }

    @Test
    void 번들_스킬이_전부_실린다() throws Exception {
        JsonNode skills = skills();
        List<String> names = new ArrayList<>();
        skills.forEach(s -> names.add(s.path("name").asText()));
        assertThat(names).contains("fdc-trace-reading", "fdc-explain-sensor");
    }

    @Test
    void skill은_툴이름과_같은_언더스코어_표기다() throws Exception {
        // 입력 카드 회신이 inputs[skill][key] 로 맞물리려면 툴 이름과 같아야 한다.
        JsonNode trace = byName(skills(), "fdc-trace-reading");
        assertThat(trace).isNotNull();
        assertThat(trace.path("skill").asText()).isEqualTo("fdc_trace_reading");
    }

    @Test
    void 인자는_required와_설명까지_그대로_편다() throws Exception {
        JsonNode trace = byName(skills(), "fdc-trace-reading");
        assertThat(trace).isNotNull();

        List<String> keys = new ArrayList<>();
        trace.path("inputs").forEach(i -> keys.add(i.path("key").asText()));
        assertThat(keys).containsExactly("equipment", "param_index", "start", "end");

        JsonNode paramIndex = trace.path("inputs").get(1);
        assertThat(paramIndex.path("required").asBoolean()).isTrue();
        assertThat(paramIndex.path("description").asText()).isNotEmpty();
    }

    @Test
    void 조달은_SQL과_bind_출처를_구분해_준다() throws Exception {
        // argBinds = 사용자가 채울 수 있는 자리, priorQueryBinds = 앞 조회 결과가 채우는 자리.
        JsonNode trace = byName(skills(), "fdc-trace-reading");
        assertThat(trace).isNotNull();

        JsonNode stats = byId(trace.path("queries"), "reading_stats");
        assertThat(stats.path("sql").asText()).contains(":eqp");
        assertThat(stats.path("argBinds").path("eqp").asText()).isEqualTo("equipment");
        assertThat(stats.path("argBinds").path("pidx").asText()).isEqualTo("param_index");
        assertThat(stats.path("priorQueryBinds")).isEmpty();

        // explain-sensor 의 설비 조회는 센서 조회 결과(EQP_ID)로 묶인다 — FE 가 못 채우는 자리.
        JsonNode explain = byName(skills(), "fdc-explain-sensor");
        assertThat(explain).isNotNull();
        JsonNode dependent = byId(explain.path("queries"), "equipment_row");
        List<String> prior = new ArrayList<>();
        dependent.path("priorQueryBinds").forEach(b -> prior.add(b.asText()));
        assertThat(prior).containsExactly("eqp");
        assertThat(dependent.path("argBinds")).isEmpty();
    }

    @Test
    void 조달은_bind_배선_전문을_준다() throws Exception {
        // binds = 배선 전문 — FE 가 판정 왕복 없이 슬롯 상태를 파생하는 재료(dataList).
        JsonNode trace = byName(skills(), "fdc-trace-reading");
        assertThat(trace).isNotNull();
        JsonNode stats = byId(trace.path("queries"), "reading_stats");
        assertThat(stats.path("binds").path("eqp").path("from").asText()).isEqualTo("arg");
        assertThat(stats.path("binds").path("eqp").path("arg").asText()).isEqualTo("equipment");
        assertThat(stats.path("binds").path("eqp").has("query")).isFalse();

        JsonNode explain = byName(skills(), "fdc-explain-sensor");
        assertThat(explain).isNotNull();
        JsonNode wired = byId(explain.path("queries"), "equipment_row").path("binds").path("eqp");
        assertThat(wired.path("from").asText()).isEqualTo("query");
        assertThat(wired.path("query").asText()).isEqualTo("sensor_row");
        assertThat(wired.path("column").asText()).isEqualTo("EQP_ID");
        assertThat(wired.has("arg")).isFalse();
    }

    @Test
    void needs_가_실려_FE_가_갈래를_로컬로_판정한다() throws Exception {
        // v2 에는 "이 조회가 지금 필요한가"를 FE 가 알 재료가 없었다 — when 이 그 자리다.
        JsonNode explain = byName(skills(), "fdc-explain-sensor");
        assertThat(explain).isNotNull();

        JsonNode gated = byId(explain.path("needs"), "equipment_active");
        assertThat(gated.path("when").asText()).isEqualTo("sensor_active = N");
        assertThat(gated.path("filledBy").get(0).path("query").asText()).isEqualTo("equipment_row");
        assertThat(gated.path("filledBy").get(0).path("column").asText()).isEqualTo("USE_YN");

        // 조건 없는 need 는 when 을 아예 싣지 않는다(있음/없음이 곧 조건부 여부).
        assertThat(byId(explain.path("needs"), "sensor_active").has("when")).isFalse();
    }

    /** {@code id} 로 배열 원소 하나 — 목록 순서에 기대지 않는다(카탈로그는 순서가 없다). */
    private static JsonNode byId(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.path("id").asText())) {
                return node;
            }
        }
        throw new AssertionError("id 없음: " + id);
    }
}

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
    void 스텝은_SQL과_bind_출처를_구분해_준다() throws Exception {
        // argBinds = 사용자가 채울 수 있는 자리, priorStepBinds = 앞 조회 결과가 채우는 자리.
        JsonNode trace = byName(skills(), "fdc-trace-reading");
        assertThat(trace).isNotNull();

        JsonNode step0 = trace.path("steps").get(0);
        assertThat(step0.path("sql").asText()).contains(":eqp");
        assertThat(step0.path("argBinds").path("eqp").asText()).isEqualTo("equipment");
        assertThat(step0.path("argBinds").path("pidx").asText()).isEqualTo("param_index");
        assertThat(step0.path("priorStepBinds")).isEmpty();

        // explain-sensor 2단계는 1단계 결과(EQP_ID)로 묶인다 — FE 가 채울 수 없는 자리.
        JsonNode explain = byName(skills(), "fdc-explain-sensor");
        assertThat(explain).isNotNull();
        JsonNode dependent = explain.path("steps").get(1);
        List<String> prior = new ArrayList<>();
        dependent.path("priorStepBinds").forEach(b -> prior.add(b.asText()));
        assertThat(prior).containsExactly("eqp");
        assertThat(dependent.path("argBinds")).isEmpty();
    }

    @Test
    void 스텝은_bind_배선_전문을_준다() throws Exception {
        // binds = 배선 전문 — FE 가 판정 왕복 없이 스텝 상태를 파생하는 재료(dataList).
        JsonNode trace = byName(skills(), "fdc-trace-reading");
        assertThat(trace).isNotNull();
        JsonNode step0 = trace.path("steps").get(0);
        assertThat(step0.path("binds").path("eqp").path("from").asText()).isEqualTo("arg");
        assertThat(step0.path("binds").path("eqp").path("arg").asText()).isEqualTo("equipment");
        assertThat(step0.path("binds").path("eqp").has("step")).isFalse();

        JsonNode explain = byName(skills(), "fdc-explain-sensor");
        assertThat(explain).isNotNull();
        JsonNode wired = explain.path("steps").get(1).path("binds").path("eqp");
        assertThat(wired.path("from").asText()).isEqualTo("step");
        assertThat(wired.path("step").asInt()).isEqualTo(0);
        assertThat(wired.path("column").asText()).isEqualTo("EQP_ID");
        assertThat(wired.has("arg")).isFalse();
    }

    @Test
    void 스텝의_분기가_거울로_내려간다() throws Exception {
        // branches — FE 가 조건부 스텝(opens 대상 = 잠김 출생)을 파생하는 재료(#55).
        JsonNode trace = byName(skills(), "fdc-trace-reading");
        assertThat(trace).isNotNull();
        JsonNode branch = trace.path("steps").get(0).path("branches").get(0);
        assertThat(branch.path("when").asText()).isEqualTo("CNT = 0");
        assertThat(branch.path("then").asText()).contains("측정이 없다");
        assertThat(branch.has("opens")).isFalse(); // 종료형 — opens 없음.

        // 분기 없는 스텝은 필드 자체가 없다.
        assertThat(trace.path("steps").get(1).has("branches")).isFalse();
    }
}

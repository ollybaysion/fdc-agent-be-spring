package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** 에이전트(mock LLM) SSE 계약. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatAgentApiTest {

    @Autowired
    private MockMvc mvc;

    private MockHttpServletResponse chat(String userContent) throws Exception {
        return mvc.perform(post("/api/fdc/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"" + userContent + "\"}]}"))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse chatWithBody(String json) throws Exception {
        return mvc.perform(post("/api/fdc/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse();
    }

    @Test
    void 설비_상세_질문은_표가_아니라_dataRequests로_조달을_요청한다() throws Exception {
        // 전용 설비 조회 툴이 없어진 뒤로 BE 는 설비 정보를 직접 만들지 않는다 —
        // 실행 가능한 SQL 을 담은 요청 카드를 내보내고, 사용자가 채워 넣는다.
        MockHttpServletResponse res = chat("ETCH-01 설비 정보 보여줘");
        assertThat(res.getStatus()).isEqualTo(200);
        String body = res.getContentAsString(StandardCharsets.UTF_8);

        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done).isNotNull();
        assertThat(done.path("finishReason").asText()).isEqualTo("stop");
        // 조회한 게 없으니 표는 실리지 않는다.
        assertThat(done.has("tables")).isFalse();

        JsonNode req = done.path("dataRequests").get(0);
        assertThat(req.path("queryKey").asText()).isEqualTo("equipment_detail");
        assertThat(req.path("sql").asText()).contains("fdc_equipment");
        assertThat(SseTestSupport.tokenText(body)).contains("데이터 요청을 등록");
    }

    @Test
    void 동종_설비_질문도_dataRequests로_요청된다() throws Exception {
        MockHttpServletResponse res = chat("ETCH-01 동종 설비 알려줘");
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done).isNotNull();

        JsonNode req = done.path("dataRequests").get(0);
        assertThat(req.path("queryKey").asText()).isEqualTo("equipment_peers");
        assertThat(req.path("label").asText()).isEqualTo("동종 설비 목록");

        List<String> cols = new ArrayList<>();
        req.path("columns").forEach(c -> cols.add(c.asText()));
        assertThat(cols).contains("EQP_ID", "MODEL_CD");
    }

    @Test
    void 설비_ID_없는_일반_질문은_툴_없이_안내한다() throws Exception {
        MockHttpServletResponse res = chat("안녕하세요");
        assertThat(res.getStatus()).isEqualTo(200);
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done).isNotNull();
        assertThat(done.has("tables")).isFalse();
        assertThat(SseTestSupport.tokenText(body)).contains("설비 ID");
    }

    @Test
    void 붙여넣은_스냅샷은_임시DB로_적재돼_query_snapshot으로_조회된다() throws Exception {
        // 📌 pinned 스냅샷(rows 있음)은 임시 SQLite 로 적재된다(Design B). 설비/센서 ID 가
        // 없는 "이 데이터" 질문이라 mock LLM 은 query_snapshot 을 호출하고, 그 결과 표가
        // done 에 실린다 — 행은 프롬프트에 붓지 않고 조회로 가져온다.
        String body = """
                {"messages":[{"role":"user","content":"이 데이터로 분석해줘"}],
                 "dataSnapshots":[
                   {"queryKey":"sensor_list","label":"챔버별 센서","capturedAt":"2026-07-22T00:00",
                    "columns":["CHAMBER","SENSOR"],"rowCount":2,
                    "rows":[["챔버A","온도"],[null,"압력"]]}
                 ]}
                """;
        MockHttpServletResponse res = chatWithBody(body);
        assertThat(res.getStatus()).isEqualTo(200);
        String out = res.getContentAsString(StandardCharsets.UTF_8);

        JsonNode done = SseTestSupport.donePayload(out);
        assertThat(done).isNotNull();
        assertThat(done.path("finishReason").asText()).isEqualTo("stop");
        assertThat(done.path("tables").isArray()).isTrue();

        // query_snapshot 결과 표에 적재된 셀 값이 담긴다(프롬프트가 아니라 조회 결과로).
        List<String> sensors = new ArrayList<>();
        done.path("tables").get(0).path("rows").forEach(r -> sensors.add(r.path("SENSOR").asText()));
        assertThat(sensors).contains("온도", "압력");
    }

    @Test
    void 조회할_수_없는_데이터는_done에_dataRequests로_요청된다() throws Exception {
        // 설비/센서 ID 없이 특정 데이터를 요구 → mock LLM 이 request_data 를 호출하고,
        // 에이전트가 done 페이로드에 요청 카드로 실어 보낸다.
        MockHttpServletResponse res = chat("센서 목록 알려줘");
        assertThat(res.getStatus()).isEqualTo(200);
        String body = res.getContentAsString(StandardCharsets.UTF_8);

        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done).isNotNull();
        assertThat(done.path("dataRequests").isArray()).isTrue();

        JsonNode req = done.path("dataRequests").get(0);
        assertThat(req.path("queryKey").asText()).isEqualTo("sensor_list");
        assertThat(req.path("label").asText()).isEqualTo("챔버별 센서 목록");
        assertThat(req.path("sql").asText()).contains("fdc_sensor_master");
        List<String> cols = new ArrayList<>();
        req.path("columns").forEach(c -> cols.add(c.asText()));
        assertThat(cols).contains("CHAMBER", "SENSOR_ID", "SENSOR_NAME");

        // 사용자에게 조달을 요청하는 안내 문구도 스트림된다.
        assertThat(SseTestSupport.tokenText(body)).contains("데이터 요청을 등록");
    }

    @Test
    void 이미_제공된_데이터는_dataRequests로_다시_요청하지_않는다() throws Exception {
        // 같은 queryKey(recipe_steps) 스냅샷을 동봉하면, mock 이 request_data 를 불러도
        // 에이전트가 queryKey 로 억제해 요청 카드를 내보내지 않는다(왕복 종료).
        String body = """
                {"messages":[{"role":"user","content":"레시피 STEP 알려줘"}],
                 "dataSnapshots":[
                   {"queryKey":"recipe_steps","label":"레시피 STEP 구성","capturedAt":"2026-07-22T00:00",
                    "columns":["STEP_NO","STEP_NAME"],"rowCount":1,"rows":[["1","가열"]]}
                 ]}
                """;
        MockHttpServletResponse res = chatWithBody(body);
        assertThat(res.getStatus()).isEqualTo(200);

        JsonNode done = SseTestSupport.donePayload(res.getContentAsString(StandardCharsets.UTF_8));
        assertThat(done).isNotNull();
        // 이미 제공됐으므로 요청 카드가 나가지 않는다(필드 생략).
        assertThat(done.has("dataRequests")).isFalse();
    }

    @Test
    void 스킬_인자가_없으면_done에_inputRequests로_요청된다() throws Exception {
        // 측정 분석인데 param_index(센서) 없음 → mock LLM 이 request_input 을 호출하고,
        // 에이전트가 done 페이로드에 입력 카드로 실어 보낸다(스킬 태그 포함).
        MockHttpServletResponse res = chat("CVD-01 측정 분석해줘");
        assertThat(res.getStatus()).isEqualTo(200);
        String body = res.getContentAsString(StandardCharsets.UTF_8);

        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done).isNotNull();
        assertThat(done.path("inputRequests").isArray()).isTrue();

        JsonNode req = done.path("inputRequests").get(0);
        assertThat(req.path("skill").asText()).isEqualTo("fdc_trace_reading");
        assertThat(req.path("key").asText()).isEqualTo("param_index");
        assertThat(req.path("label").asText()).isEqualTo("PARAM_INDEX");

        // 입력을 청하는 안내 문구도 스트림된다.
        assertThat(SseTestSupport.tokenText(body)).contains("입력 요청을 등록");
    }

    @Test
    void 이미_제공된_입력은_done에_inputRequests로_다시_요청하지_않는다() throws Exception {
        // inputs 로 param_index 를 실어 보내면, mock 이 재요청해도 에이전트가 억제한다.
        String body = """
                {"messages":[{"role":"user","content":"CVD-01 측정 분석해줘"}],
                 "inputs":{"fdc_trace_reading":{"param_index":"7"}}}
                """;
        MockHttpServletResponse res = chatWithBody(body);
        assertThat(res.getStatus()).isEqualTo(200);

        JsonNode done = SseTestSupport.donePayload(res.getContentAsString(StandardCharsets.UTF_8));
        assertThat(done).isNotNull();
        // 이미 제공됐으므로 입력 카드가 나가지 않는다(필드 생략).
        assertThat(done.has("inputRequests")).isFalse();
    }
}

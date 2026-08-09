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
    void 조달_요청은_등재된_풀에서_골라_실행_가능한_SQL로_나간다() throws Exception {
        // BE 가 조회할 수 없는 데이터는 요청 카드로 나간다. 카드의 SQL·조회 키·기대 컬럼은
        // 전부 BE 가 만든다 — 모델은 풀에서 고르고 인자만 채웠다.
        MockHttpServletResponse res = chat("S-0004 조회 SQL 로 요청해줘");
        assertThat(res.getStatus()).isEqualTo(200);
        String body = res.getContentAsString(StandardCharsets.UTF_8);

        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done).isNotNull();
        assertThat(done.path("finishReason").asText()).isEqualTo("stop");
        // 조회한 게 없으니 표는 실리지 않는다.
        assertThat(done.has("tables")).isFalse();

        JsonNode req = done.path("dataRequests").get(0);
        assertThat(req.path("queryKey").asText()).isEqualTo("fdc-explain-sensor#sensor_row__snsr_id=S-0004");
        assertThat(req.path("sql").asText())
                .contains("FROM fdc_sensor").contains("snsr_id = 'S-0004'").doesNotContain(":id");

        List<String> cols = new ArrayList<>();
        req.path("columns").forEach(c -> cols.add(c.asText()));
        assertThat(cols).contains("SNSR_ID", "EQP_ID");
        assertThat(SseTestSupport.tokenText(body)).contains("데이터 요청을 등록");
    }

    @Test
    void 붙여넣은_결과가_도착하면_다음_단계를_이어_요청한다() throws Exception {
        // 진행 상태를 어디에도 저장하지 않는다 — 도착한 스냅샷의 조회 키가 곧 "1단계까지
        // 왔다"이고, 2단계의 바인드(EQP_ID)는 붙여넣은 표에서 읽는다.
        String body = """
                {"messages":[{"role":"user","content":"등록 완료"}],
                 "dataSnapshots":[
                   {"queryKey":"fdc-explain-sensor#sensor_row__snsr_id=S-0004","label":"1단계 — 센서 기본 정보",
                    "capturedAt":"2026-07-28T00:00","columns":["SNSR_ID","EQP_ID"],"rowCount":1,
                    "rows":[["S-0004","CVD-01"]]}
                 ]}
                """;
        MockHttpServletResponse res = chatWithBody(body);
        assertThat(res.getStatus()).isEqualTo(200);

        JsonNode done = SseTestSupport.donePayload(res.getContentAsString(StandardCharsets.UTF_8));
        assertThat(done).isNotNull();

        JsonNode req = done.path("dataRequests").get(0);
        assertThat(req.path("queryKey").asText()).isEqualTo("fdc-explain-sensor#equipment_row__snsr_id=S-0004");
        assertThat(req.path("sql").asText()).contains("eqp_id = 'CVD-01'");
    }

    @Test
    void 결과_없음으로_등록하면_절차를_더_진행하지_않는다() throws Exception {
        // 0행은 "아직 안 왔다"가 아니라 "없다"이다 — 다음 단계를 요청하지 않고 끝난다.
        String body = """
                {"messages":[{"role":"user","content":"등록 완료"}],
                 "dataSnapshots":[
                   {"queryKey":"fdc-explain-sensor#sensor_row__snsr_id=S-9999","label":"1단계 — 센서 기본 정보",
                    "capturedAt":"2026-07-28T00:00","columns":["SNSR_ID","EQP_ID"],"rowCount":0,
                    "rows":[]}
                 ]}
                """;
        MockHttpServletResponse res = chatWithBody(body);
        assertThat(res.getStatus()).isEqualTo(200);

        JsonNode done = SseTestSupport.donePayload(res.getContentAsString(StandardCharsets.UTF_8));
        assertThat(done).isNotNull();
        assertThat(done.has("dataRequests")).isFalse();
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
    void 절차가_끝까지_도착하면_더_요청하지_않는다() throws Exception {
        // 억제는 모델의 판단이 아니라 도착한 조회 키로 확정된다(왕복 종료).
        String body = """
                {"messages":[{"role":"user","content":"등록 완료"}],
                 "dataSnapshots":[
                   {"queryKey":"fdc-explain-sensor#sensor_row__snsr_id=S-0004","label":"1단계",
                    "capturedAt":"2026-07-28T00:00","columns":["SNSR_ID","EQP_ID"],"rowCount":1,
                    "rows":[["S-0004","CVD-01"]]},
                   {"queryKey":"fdc-explain-sensor#equipment_row__snsr_id=S-0004","label":"2단계",
                    "capturedAt":"2026-07-28T00:01","columns":["EQP_ID","EQP_NAME"],"rowCount":1,
                    "rows":[["CVD-01","증착기 1호"]]},
                   {"queryKey":"fdc-explain-sensor#setup_event_rows__snsr_id=S-0004","label":"3단계",
                    "capturedAt":"2026-07-28T00:02","columns":["D","EVT_LABEL"],"rowCount":1,
                    "rows":[["2026-05-11","라인 점검"]]}
                 ]}
                """;
        MockHttpServletResponse res = chatWithBody(body);
        assertThat(res.getStatus()).isEqualTo(200);

        JsonNode done = SseTestSupport.donePayload(res.getContentAsString(StandardCharsets.UTF_8));
        assertThat(done).isNotNull();
        // 남은 단계가 없으므로 요청 카드가 나가지 않는다(필드 생략).
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

    @Test
    void 질문에_센서가_둘_이상이면_done에_choiceRequests로_선택_카드가_나간다() throws Exception {
        // 후보를 2개 이상으로 좁혔을 때(여기서는 mock 이 질문 속 서로 다른 센서 ID 를
        // 후보로 본다) choice_request 가 done.choiceRequests 로 나간다(#53).
        MockHttpServletResponse res = chat("S-0004 S-0005 중 어디를 볼까요?");
        assertThat(res.getStatus()).isEqualTo(200);
        String body = res.getContentAsString(StandardCharsets.UTF_8);

        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done).isNotNull();
        assertThat(done.path("choiceRequests").isArray()).isTrue();

        JsonNode req = done.path("choiceRequests").get(0);
        assertThat(req.path("question").asText()).isEqualTo("어느 센서를 분석할까요?");
        assertThat(req.path("multiSelect").asBoolean()).isTrue();

        List<String> labels = new ArrayList<>();
        req.path("options").forEach(o -> labels.add(o.path("label").asText()));
        assertThat(labels).containsExactly("S-0004", "S-0005");

        assertThat(SseTestSupport.tokenText(body)).contains("선택지를 제시했습니다");
    }

    @Test
    void 선택_요청이_없으면_done에_choiceRequests가_없다() throws Exception {
        MockHttpServletResponse res = chat("안녕하세요");
        assertThat(res.getStatus()).isEqualTo(200);

        JsonNode done = SseTestSupport.donePayload(res.getContentAsString(StandardCharsets.UTF_8));
        assertThat(done).isNotNull();
        assertThat(done.has("choiceRequests")).isFalse();
    }
}

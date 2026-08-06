package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * POST /api/fdc/v1/chat/data (panel-judge, #38) SSE 계약 — 판정은 done 에
 * 선언적으로, 종결 서술만 token 스트림으로.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatDataApiTest {

    private static final String KEY0 = "fdc-explain-sensor#sensor_row__snsr_id=S-0004";

    @Autowired
    private MockMvc mvc;

    private MockHttpServletResponse chatData(String json) throws Exception {
        return mvc.perform(post("/api/fdc/v1/chat/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse();
    }

    @Test
    void 선언된_절차의_진행이_done_만으로_나온다() throws Exception {
        MockHttpServletResponse res = chatData("""
                {"eventId":"e1","revision":3,
                 "runs":[{"skill":"fdc-explain-sensor","args":{"snsr_id":"S-0004"}}]}
                """);
        assertThat(res.getStatus()).isEqualTo(200);
        String body = res.getContentAsString(StandardCharsets.UTF_8);

        // 서술이 없으니 token 이벤트는 없다 — done 만.
        assertThat(SseTestSupport.tokenText(body)).isEmpty();
        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done).isNotNull();
        assertThat(done.path("eventId").asText()).isEqualTo("e1");
        assertThat(done.path("revision").asInt()).isEqualTo(3);
        assertThat(done.path("poolRev").asText()).isNotEmpty();
        assertThat(done.has("openRequests")).isFalse();
        assertThat(done.path("runsProgress").get(0).path("outcome").asText())
                .isEqualTo("PROCURABLE");
    }

    @Test
    void 조달_원장이_상태와_함께_전량_나간다() throws Exception {
        // 원장은 이벤트가 아니라 상태 전량 — 잠긴 줄도 실리고 FE 는 replace 한다.
        MockHttpServletResponse res = chatData("""
                {"eventId":"e9","revision":1,
                 "runs":[{"skill":"fdc-explain-sensor","args":{"snsr_id":"S-0004"}}]}
                """);
        JsonNode ledger = SseTestSupport.donePayload(res.getContentAsString(StandardCharsets.UTF_8))
                .path("dataRequests");

        assertThat(ledger.isArray()).isTrue();
        assertThat(ledger).isNotEmpty();
        JsonNode first = ledger.get(0);
        assertThat(first.path("queryKey").asText()).isEqualTo(KEY0);
        assertThat(first.path("state").asText()).isEqualTo("ready");
        assertThat(first.path("sql").asText()).contains("S-0004");
        assertThat(first.path("run").path("skill").asText()).isEqualTo("fdc-explain-sensor");
        // 앞 조달을 무는 줄은 SQL 없이 사유만 — 화면은 이 상태를 그대로 그린다.
        JsonNode blocked = null;
        for (JsonNode row : ledger) {
            if ("blocked".equals(row.path("state").asText())) {
                blocked = row;
                break;
            }
        }
        assertThat(blocked).isNotNull();
        assertThat(blocked.has("sql")).isFalse();
        assertThat(blocked.path("blocked").asText()).isNotEmpty();
    }

    @Test
    void 조달이_도착하면_그_need_가_찬_것으로_보고된다() throws Exception {
        MockHttpServletResponse res = chatData("""
                {"eventId":"e2","revision":4,
                 "runs":[{"skill":"fdc-explain-sensor","args":{"snsr_id":"S-0004"}}],
                 "snapshots":[
                   {"queryKey":"%s","label":"1단계","capturedAt":"2026-08-01T00:00",
                    "columns":["SNSR_ID","EQP_ID"],"rowCount":1,"rows":[["S-0004","CVD-01"]]}
                 ]}
                """.formatted(KEY0));
        JsonNode done = SseTestSupport.donePayload(res.getContentAsString(StandardCharsets.UTF_8));

        JsonNode run = done.path("runsProgress").get(0);
        // EQP_ID 는 왔지만 종류·단위·상태 컬럼은 이 표에 없다 — 찬 것만 찬 것으로 센다.
        assertThat(run.path("metCount").asInt()).isEqualTo(1);
        assertThat(run.path("outcome").asText()).isEqualTo("PROCURABLE");
        assertThat(run.path("wanted").get(0).asText())
                .isEqualTo("fdc-explain-sensor#equipment_row");
        assertThat(run.path("terminal").asBoolean()).isFalse();
    }

    @Test
    void 영행_등록이_절차를_끝내면_그_응답에서_서술이_스트리밍된다() throws Exception {
        MockHttpServletResponse res = chatData("""
                {"eventId":"e3","revision":5,
                 "event":{"type":"snapshot-registered","queryKey":"%s"},
                 "messages":[{"role":"user","content":"S-0004 설명해줘"}],
                 "runs":[{"skill":"fdc-explain-sensor","args":{"snsr_id":"S-0004"}}],
                 "snapshots":[
                   {"queryKey":"%s","label":"1단계","capturedAt":"2026-08-01T00:00",
                    "columns":["SNSR_ID","EQP_ID"],"rowCount":0,"rows":[]}
                 ]}
                """.formatted(KEY0, KEY0));
        String body = res.getContentAsString(StandardCharsets.UTF_8);

        // 종결 서술이 합성 사용자 발화 없이 이 응답의 token 스트림으로 나온다.
        assertThat(SseTestSupport.tokenText(body)).contains("조회 절차가 완료");
        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done.path("narratedRun").asText()).isEqualTo("fdc-explain-sensor (snsr_id=S-0004)");
        assertThat(done.path("terminalRuns").get(0).asText())
                .isEqualTo("fdc-explain-sensor (snsr_id=S-0004)");
    }

    @Test
    void 모르는_role_메시지는_드롭되고_판정은_계속된다() throws Exception {
        // T11: role 하나가 낯설다고 자동 호출 경로가 400 으로 무음 고장나지 않는다.
        MockHttpServletResponse res = chatData("""
                {"eventId":"e4",
                 "messages":[{"role":"error","content":"이전 오류"},{"role":"user","content":"질문"}],
                 "runs":[{"skill":"fdc-explain-sensor","args":{"snsr_id":"S-0004"}}]}
                """);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(SseTestSupport.donePayload(res.getContentAsString(StandardCharsets.UTF_8))
                .path("runsProgress")).hasSize(1);
    }

    @Test
    void 채팅_인렛도_모르는_role_을_드롭한다() throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/api/fdc/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":[{"role":"error","content":"이전 오류"},
                                             {"role":"user","content":"안녕"}]}
                                """))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void body_가_없으면_400() throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/api/fdc/v1/chat/data")
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(400);
    }
}

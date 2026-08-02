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

    private static final String KEY0 = "fdc-explain-sensor#0__snsr_id=S-0004";

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

        // 서술이 없으니 token 이벤트는 없다 — done 만. 카드는 응답에 없다(FE 로컬 판정).
        assertThat(SseTestSupport.tokenText(body)).isEmpty();
        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done).isNotNull();
        assertThat(done.path("eventId").asText()).isEqualTo("e1");
        assertThat(done.path("revision").asInt()).isEqualTo(3);
        assertThat(done.path("poolRev").asText()).isNotEmpty();
        assertThat(done.has("openRequests")).isFalse();
        assertThat(done.path("runsProgress").get(0).path("nextStep").asInt()).isZero();
    }

    @Test
    void 단계가_도착하면_진행이_갱신된다() throws Exception {
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
        assertThat(run.path("arrivedCount").asInt()).isEqualTo(1);
        assertThat(run.path("nextStep").asInt()).isEqualTo(1);
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

    // ── 분기 갈림길 (#55) — 실 spec fdc-trace-reading 의 CNT = 0 ─────────────

    private static final String TRACE_KEY0 =
            "fdc-trace-reading#0__end=2026-07-31&equipment=CVD-01&param_index=7&start=2026-07-01";

    private String traceBody(String cnt, String anom) {
        return """
                {"eventId":"b1","revision":9,
                 "event":{"type":"snapshot-registered","queryKey":"%s"},
                 "messages":[{"role":"user","content":"CVD-01의 PARAM_INDEX 7, 7월 측정 어떻게 나온 거야?"}],
                 "runs":[{"skill":"fdc-trace-reading",
                          "args":{"equipment":"CVD-01","param_index":"7",
                                  "start":"2026-07-01","end":"2026-07-31"}}],
                 "snapshots":[
                   {"queryKey":"%s","label":"1단계","capturedAt":"2026-08-03T00:00",
                    "columns":["CNT","MEAN","SD","MINV","MAXV","ANOM"],"rowCount":1,
                    "rows":[["%s",null,null,null,null,"%s"]]}
                 ]}
                """.formatted(TRACE_KEY0, TRACE_KEY0, cnt, anom);
    }

    @Test
    void 분기_스텝_도착이_stop_판정이면_그_응답에서_절차가_끝나고_서술이_난다() throws Exception {
        // 집계는 항상 1행이라 0행 종결에 안 걸린다 — CNT=0 은 LLM(목: 단순 비교) 판정.
        MockHttpServletResponse res = chatData(traceBody("0", "0"));
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        JsonNode done = SseTestSupport.donePayload(body);

        JsonNode decision = done.path("branchDecisions").get(0);
        assertThat(decision.path("decision").asText()).isEqualTo("stop");
        assertThat(decision.path("skill").asText()).isEqualTo("fdc-trace-reading");
        assertThat(decision.path("step").asInt()).isZero();
        assertThat(decision.path("index").asInt()).isZero();
        assertThat(decision.path("reason").asText()).isNotEmpty();

        assertThat(done.path("runsProgress").get(0).path("terminal").asBoolean()).isTrue();
        assertThat(done.path("terminalRuns").get(0).asText()).contains("fdc-trace-reading");
        assertThat(done.path("narratedRun").asText()).contains("fdc-trace-reading");
        assertThat(SseTestSupport.tokenText(body)).contains("조회 절차가 완료");
    }

    @Test
    void 분기_불성립이면_continue_로_분기_없던_동작_그대로다() throws Exception {
        MockHttpServletResponse res = chatData(traceBody("412", "3"));
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        JsonNode done = SseTestSupport.donePayload(body);

        assertThat(done.path("branchDecisions").isMissingNode()).isTrue();
        // 분기 없던 동작 그대로 — 절차는 2단계를 다음으로 두고 열려 있다.
        JsonNode run = done.path("runsProgress").get(0);
        assertThat(run.path("terminal").asBoolean()).isFalse();
        assertThat(run.path("nextStep").asInt()).isEqualTo(1);
        assertThat(SseTestSupport.tokenText(body)).isEmpty();
    }
}

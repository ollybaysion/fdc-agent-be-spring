package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Node 판 test/chat.agent.test.ts 포팅 — 에이전트(mock LLM) SSE 계약.
 * SSE 스트림/검증 기본 계약 테스트는 구 EquipmentApiTest 에서 이동
 * (정형 GET 제거, 2026-07-19).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatAgentApiTest {

    @Autowired
    private MockMvc mvc;

    private static final ObjectMapper JSON = new ObjectMapper();

    private MockHttpServletResponse chat(String userContent) throws Exception {
        return mvc.perform(post("/api/fdc/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"" + userContent + "\"}]}"))
                .andReturn().getResponse();
    }

    @Test
    void 설비_ID_질문은_설비_상세_툴을_호출하고_done에_표를_동봉한다() throws Exception {
        MockHttpServletResponse res = chat("ETCH-01 설비 정보 보여줘");
        assertThat(res.getStatus()).isEqualTo(200);
        String body = res.getContentAsString(StandardCharsets.UTF_8);

        assertThat(SseTestSupport.tokenText(body)).contains("ETCH-01");

        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done).isNotNull();
        assertThat(done.path("finishReason").asText()).isEqualTo("stop");
        assertThat(done.path("tables").isArray()).isTrue();

        List<String> titles = new ArrayList<>();
        done.path("tables").forEach(t -> titles.add(t.path("title").asText()));
        assertThat(titles).containsExactly("설비 정보", "챔버 정보", "센서 정보");

        JsonNode equip = done.path("tables").get(0);
        assertThat(equip.path("columns").get(0).asText()).isEqualTo("ID");
        assertThat(equip.path("rows").get(0).path("ID").asText()).isEqualTo("ETCH-01");
    }

    @Test
    void 동종_설비_질문은_peers_툴을_호출하고_표에_ETCH_02가_포함된다() throws Exception {
        MockHttpServletResponse res = chat("ETCH-01 동종 설비 알려줘");
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        JsonNode done = SseTestSupport.donePayload(body);
        assertThat(done).isNotNull();

        JsonNode peers = null;
        for (JsonNode t : done.path("tables")) {
            if ("동종 설비".equals(t.path("title").asText())) {
                peers = t;
            }
        }
        assertThat(peers).isNotNull();
        List<String> ids = new ArrayList<>();
        peers.path("rows").forEach(r -> ids.add(r.path("ID").asText()));
        assertThat(ids).contains("ETCH-02");
        assertThat(ids).doesNotContain("ETCH-01");
    }

    @Test
    void chat은_SSE로_token_done_을_스트리밍하고_요청_헤더를_단다() throws Exception {
        MockHttpServletResponse res = chat("안녕");
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("Content-Type")).contains("text/event-stream");
        assertThat(res.getHeader("x-request-id")).isNotBlank();
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event: token");
        assertThat(body).contains("event: done");
    }

    @Test
    void messages_누락은_400_messages_required() throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/api/fdc/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(400);
        JsonNode parsed = JSON.readTree(res.getContentAsString(StandardCharsets.UTF_8));
        assertThat(parsed.path("error").asText()).isEqualTo("messages_required");
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
}

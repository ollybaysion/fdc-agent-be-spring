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

/** Node 판 test/chat.agent.test.ts 포팅 — 에이전트(mock LLM) SSE 계약. */
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
    void 붙여넣은_데이터_스냅샷은_프롬프트에_주입돼_LLM_이_근거로_본다() throws Exception {
        // 📌 pinned 스냅샷(rows 있음) + 카탈로그 항목(rows 없음)을 함께 보낸다.
        // 설비/센서 ID 가 없는 일반 질문이라 mock LLM 은 툴을 안 부르고, 주입된
        // 마지막 사용자 메시지를 그대로 되돌려준다 — 스냅샷이 프롬프트에 닿았는지 검증.
        String body = """
                {"messages":[{"role":"user","content":"이 데이터로 분석해줘"}],
                 "dataSnapshots":[
                   {"queryKey":"sensor_list","label":"챔버별 센서","capturedAt":"2026-07-22T00:00",
                    "columns":["CHAMBER","SENSOR"],"rowCount":2,
                    "rows":[["챔버A","온도"],[null,"압력"]]},
                   {"queryKey":"recipe","label":"레시피 STEP","capturedAt":"2026-07-22T00:00",
                    "columns":["STEP_NO"],"rowCount":5}
                 ]}
                """;
        MockHttpServletResponse res = chatWithBody(body);
        assertThat(res.getStatus()).isEqualTo(200);
        String text = SseTestSupport.tokenText(res.getContentAsString(StandardCharsets.UTF_8));

        // 📌 스냅샷: 라벨 + 실제 셀 값이 프롬프트(→ mock 응답)에 나타난다.
        assertThat(text).contains("챔버별 센서").contains("온도").contains("압력");
        // 카탈로그 항목: 내용 없이 존재만 알린다.
        assertThat(text).contains("레시피 STEP").contains("내용 미첨부");
    }
}

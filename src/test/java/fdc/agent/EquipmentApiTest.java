package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** Node 판 test/equipment.test.ts 포팅 — 정형 4 GET + chat 기본 SSE/검증. */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentApiTest {

    @Autowired
    private MockMvc mvc;

    private static final ObjectMapper JSON = new ObjectMapper();

    private MockHttpServletResponse getRes(String url) throws Exception {
        return mvc.perform(get(url)).andReturn().getResponse();
    }

    private static JsonNode json(MockHttpServletResponse res) throws Exception {
        return JSON.readTree(res.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void 계약에_맞는_설비_상세를_반환한다() throws Exception {
        MockHttpServletResponse res = getRes("/api/fdc/v1/equipment/ETCH-01");
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("x-request-id")).isNotBlank();
        JsonNode parsed = json(res);
        assertThat(parsed.path("id").asText()).isEqualTo("ETCH-01");
        assertThat(parsed.path("model").asText()).isEqualTo("EtcherX-2000");
        // sections 모델: 설비/챔버/센서 세션 + BE-driven 컬럼 라벨(fixture=col1..10)
        List<String> keys = new ArrayList<>();
        parsed.path("sections").forEach(s -> keys.add(s.path("key").asText()));
        assertThat(keys).containsExactly("equipment", "chamber", "sensor");
        JsonNode equip = parsed.path("sections").get(0);
        List<String> columns = new ArrayList<>();
        equip.path("columns").forEach(c -> columns.add(c.asText()));
        assertThat(columns).contains("col1");
        assertThat(equip.path("rows").get(0).path("values").size()).isEqualTo(columns.size());
    }

    @Test
    void 없는_설비는_404_not_found() throws Exception {
        MockHttpServletResponse res = getRes("/api/fdc/v1/equipment/NOPE");
        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(json(res).path("error").asText()).isEqualTo("not_found");
    }

    @Test
    void peers는_같은_model의_다른_설비만_반환한다() throws Exception {
        MockHttpServletResponse res = getRes("/api/fdc/v1/equipment/ETCH-01/peers");
        assertThat(res.getStatus()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        json(res).forEach(p -> ids.add(p.path("id").asText()));
        assertThat(ids).contains("ETCH-02", "ETCH-03");
        assertThat(ids).doesNotContain("ETCH-01", "CVD-01");
    }

    @Test
    void 계약에_맞는_셋업_이벤트_배열을_반환한다() throws Exception {
        MockHttpServletResponse res = getRes("/api/fdc/v1/equipment/ETCH-01/setup-events");
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode events = json(res);
        assertThat(events.size()).isGreaterThanOrEqualTo(1);
        assertThat(events.get(0).path("type").asText()).isEqualTo("setup");
    }

    @Test
    void 계약에_맞는_비교_결과를_반환한다() throws Exception {
        MockHttpServletResponse res = getRes(
                "/api/fdc/v1/equipment/ETCH-01/compare?peerId=ETCH-02&recipe=RECIPE_X&window=7");
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode data = json(res);
        assertThat(data.path("recipe").asText()).isEqualTo("RECIPE_X");
        assertThat(data.path("current").path("equipmentId").asText()).isEqualTo("ETCH-01");
        assertThat(data.path("baseline").path("equipmentId").asText()).isEqualTo("ETCH-02");
        assertThat(data.path("series").isArray()).isTrue();
        assertThat(data.path("current").has("matchedRun")).isTrue();
    }

    @Test
    void peerId_누락은_400_peer_not_found() throws Exception {
        MockHttpServletResponse res = getRes("/api/fdc/v1/equipment/ETCH-01/compare?recipe=RECIPE_X");
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(json(res).path("error").asText()).isEqualTo("peer_not_found");
    }

    @Test
    void 허용되지_않은_recipe는_400_unknown_recipe() throws Exception {
        MockHttpServletResponse res = getRes(
                "/api/fdc/v1/equipment/ETCH-01/compare?peerId=ETCH-02&recipe=BAD");
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(json(res).path("error").asText()).isEqualTo("unknown_recipe");
    }

    @Test
    void 허용되지_않은_window는_400_unknown_window() throws Exception {
        MockHttpServletResponse res = getRes(
                "/api/fdc/v1/equipment/ETCH-01/compare?peerId=ETCH-02&recipe=RECIPE_X&window=3");
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(json(res).path("error").asText()).isEqualTo("unknown_window");
    }

    @Test
    void chat은_SSE로_token_done_을_스트리밍한다() throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/api/fdc/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"안녕\"}]}"))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("Content-Type")).contains("text/event-stream");
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
        assertThat(json(res).path("error").asText()).isEqualTo("messages_required");
    }
}

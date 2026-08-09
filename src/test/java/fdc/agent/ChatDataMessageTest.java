package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * POST /api/fdc/v1/chat/data 메시지 판정 왕복(#64 MVP) — {@code pasted} 가 실리면
 * 패널 판정 대신 스니프 → LLM 1회 포맷팅으로 {@code formattedMessage} 하나만 답한다.
 * 실패·비메시지는 formattedMessage 없는 done(불가침) — FE 표 파싱 폴백 경로다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatDataMessageTest {

    private static final String DUMP =
            "LotProcessResult{eqpId=CVD-01, lotId=LOT-24135, "
                    + "recipe=RecipeInfo{recipeId=R-88, version=3}, "
                    + "steps=[StepResult{stepNo=1, status=OK}]}";

    @Autowired
    private MockMvc mvc;

    private JsonNode done(String json) throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/api/fdc/v1/chat/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return SseTestSupport.donePayload(res.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void 덤프_붙여넣기는_formattedMessage_로_답한다() throws Exception {
        JsonNode done = done(new ObjectMapper().writeValueAsString(
                java.util.Map.of("eventId", "e1", "revision", 1, "pasted", DUMP)));

        assertThat(done.path("eventId").asText()).isEqualTo("e1");
        JsonNode fm = done.path("formattedMessage");
        assertThat(fm.isMissingNode()).isFalse();
        // json 은 문자열이 아니라 객체다 — pretty-print 는 FE 소유.
        assertThat(fm.path("json").isObject()).isTrue();
        assertThat(fm.path("json").path("eqpId").asText()).isEqualTo("CVD-01");
        assertThat(fm.path("eqpId").asText()).isEqualTo("CVD-01");
        assertThat(fm.path("className").asText()).isEqualTo("LotProcessResult");
        assertThat(fm.path("comment").asText()).isNotEmpty();
        // 메시지 왕복은 패널 판정이 아니다 — 원장을 싣지 않는다(FE 도 replace 안 함).
        assertThat(done.has("dataRequests")).isFalse();
    }

    @Test
    void 표_텍스트는_불가침이다() throws Exception {
        JsonNode done = done("""
                {"eventId":"e2","revision":1,"pasted":"COL_A\\tCOL_B\\n1\\t2"}
                """);
        assertThat(done.path("eventId").asText()).isEqualTo("e2");
        assertThat(done.has("formattedMessage")).isFalse();
    }

    @Test
    void 명시_플래그는_스니프를_건너뛴다() throws Exception {
        // 덤프 모양이 아니어도 사용자가 메시지라고 명시하면 포맷팅한다.
        JsonNode done = done("""
                {"eventId":"e3","revision":1,"pasted":"eqpId=CVD-02 alarm=AL-201",
                 "pastedForce":true}
                """);
        assertThat(done.path("formattedMessage").path("json").isObject()).isTrue();
    }

}

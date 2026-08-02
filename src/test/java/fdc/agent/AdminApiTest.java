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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** POST /admin/reload — akg 소스 강제 리로드(#40) 계약. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    void akg_미구성이면_not_configured로_정직하게_보고한다() throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/admin/reload")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);

        JsonNode body = JSON.readTree(res.getContentAsString(StandardCharsets.UTF_8));
        // akg 미구성 → 스킬은 classpath 번들이 그대로 살아 있고(개수>0), 리로드할 것은 없다.
        assertThat(body.path("skills").path("source").asText()).isEqualTo("bundle");
        assertThat(body.path("skills").path("outcome").asText()).isEqualTo("not-configured");
        assertThat(body.path("skills").path("count").asInt()).isGreaterThan(0);
        // 라인은 지어내지 않는다 — 미구성이면 빈 목록.
        assertThat(body.path("lines").path("source").asText()).isEqualTo("none");
        assertThat(body.path("lines").path("outcome").asText()).isEqualTo("not-configured");
        assertThat(body.path("lines").path("count").asInt()).isZero();
    }
}

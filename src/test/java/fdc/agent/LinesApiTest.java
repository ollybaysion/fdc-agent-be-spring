package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/fdc/v1/lines — 설비 등록 폼의 라인 드롭다운 계약. */
@SpringBootTest
@AutoConfigureMockMvc
class LinesApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    void akg_미설정이면_200_에_빈_목록이다() throws Exception {
        // 라인을 모르는 것과 서버가 죽은 것은 다르다 — 500 이 아니라 빈 목록이다.
        // BE 가 라인 코드를 지어내지 않으므로, 허브가 없으면 고를 것도 없는 게 맞다.
        MockHttpServletResponse res = mvc.perform(get("/api/fdc/v1/lines"))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode lines = JSON.readTree(res.getContentAsString(StandardCharsets.UTF_8)).path("lines");
        assertThat(lines.isArray()).isTrue();
        assertThat(lines).isEmpty();
    }
}

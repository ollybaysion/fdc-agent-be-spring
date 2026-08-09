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
 * POST /api/fdc/v1/chat/image — 캡처 화면 분류 인렛 계약(#63).
 *
 * <p>목 LLM 경로라 후보는 카탈로그 앞 3개로 결정론이다 — 여기서 지키는 것은 분류
 * 품질이 아니라 <b>카드가 그릴 수 있는 형태로 나가는가</b>와 <b>항상 200 인가</b>다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatImageApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 1×1 투명 PNG — 목 경로라 내용은 안 보지만 형식은 진짜여야 한다. */
    private static final String PNG_1PX =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC"
                    + "AAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

    @Autowired
    private MockMvc mvc;

    private MockHttpServletResponse send(String body) throws Exception {
        return mvc.perform(post("/api/fdc/v1/chat/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    private JsonNode classify(String dataUrl) throws Exception {
        MockHttpServletResponse res =
                send("{\"image\":\"" + dataUrl + "\"}");
        assertThat(res.getStatus()).isEqualTo(200);
        return JSON.readTree(res.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void 후보는_카드_행_형태로_나간다() throws Exception {
        JsonNode out = classify(PNG_1PX);
        JsonNode candidates = out.path("candidates");
        // 목은 카탈로그 앞 3개를 고른다(번들 화면이 정확히 3장).
        assertThat(candidates).hasSize(3);

        JsonNode first = candidates.get(0);
        assertThat(first.path("value").asText()).isEqualTo("fdc-monitor-history");
        assertThat(first.path("label").asText()).isEqualTo("센서값 이력 조회");
        // caption 은 program › menuPath — 카드가 후보 아래 한 줄로 적는다.
        assertThat(first.path("caption").asText()).isEqualTo("FDC Monitor › 이력조회");
    }

    @Test
    void browse_는_program_으로_묶여_2열_브라우저_재료가_된다() throws Exception {
        JsonNode browse = classify(PNG_1PX).path("browse");

        List<String> groups = new ArrayList<>();
        browse.forEach(g -> groups.add(g.path("group").asText()));
        assertThat(groups).containsExactly("FDC Monitor", "SPC");

        // 왼쪽 열을 누르면 오른쪽에 뜰 것들 — 후보와 같은 {value,label,caption} 어휘다.
        JsonNode monitor = browse.get(0).path("options");
        assertThat(monitor).hasSize(2);
        assertThat(monitor.get(0).path("value").asText()).isEqualTo("fdc-monitor-history");
        assertThat(monitor.get(1).path("value").asText()).isEqualTo("fdc-monitor-trend");

        JsonNode spc = browse.get(1).path("options");
        assertThat(spc).hasSize(1);
        assertThat(spc.get(0).path("value").asText()).isEqualTo("spc-control-chart");
        assertThat(spc.get(0).path("caption").asText()).isEqualTo("SPC › 관리도");
    }

    @Test
    void browse_는_후보와_무관하게_카탈로그_전체다() throws Exception {
        // ④ 는 "후보가 틀렸을 때 직접 찾는 길"이라 후보에 든 화면도 목록에 그대로 있어야 한다.
        JsonNode out = classify(PNG_1PX);
        List<String> browsed = new ArrayList<>();
        out.path("browse").forEach(g -> g.path("options")
                .forEach(o -> browsed.add(o.path("value").asText())));
        assertThat(browsed).containsExactlyInAnyOrder(
                "fdc-monitor-history", "fdc-monitor-trend", "spc-control-chart");
    }

    @Test
    void 본문이_없으면_400() throws Exception {
        assertThat(send("").getStatus()).isEqualTo(400);
    }

    @Test
    void image_가_비면_400_이유를_준다() throws Exception {
        MockHttpServletResponse res = send("{}");
        assertThat(res.getStatus()).isEqualTo(400);
        JsonNode body = JSON.readTree(res.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("error").asText()).isEqualTo("image_required");

        assertThat(send("{\"image\":\"  \"}").getStatus()).isEqualTo(400);
    }

    @Test
    void data_URL_이_아니면_400() throws Exception {
        MockHttpServletResponse res = send("{\"image\":\"https://example.com/a.png\"}");
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(res.getContentAsString(StandardCharsets.UTF_8))
                .path("error").asText()).isEqualTo("image_invalid");

        // data: 로 시작해도 base64 표시가 없으면 받지 않는다.
        assertThat(send("{\"image\":\"data:image/png,rawbytes\"}").getStatus()).isEqualTo(400);
    }

    @Test
    void 목록에_없는_형식은_400() throws Exception {
        // FE 와 같은 목록(png·jpeg·webp·gif)이다 — svg 는 벡터라 vision 경로가 다르다.
        MockHttpServletResponse res = send("{\"image\":\"data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=\"}");
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(res.getContentAsString(StandardCharsets.UTF_8))
                .path("error").asText()).isEqualTo("image_unsupported");
    }

    @Test
    void 상한을_넘는_그림은_400() throws Exception {
        // 5MB 를 디코드해 보지 않고 base64 길이로 잰다 — 4글자가 3바이트.
        String big = "A".repeat(5 * 1024 * 1024 / 3 * 4 + 8);
        MockHttpServletResponse res = send("{\"image\":\"data:image/png;base64," + big + "\"}");
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(res.getContentAsString(StandardCharsets.UTF_8))
                .path("error").asText()).isEqualTo("image_too_large");
    }

    @Test
    void 상한_안쪽은_통과한다() throws Exception {
        // 경계 바로 아래 — 큰 캡처가 형식만 맞으면 들어와야 한다(상한이 실질 금지선이 되면 안 된다).
        String ok = "A".repeat(4 * 1024 * 1024 / 3 * 4);
        MockHttpServletResponse res = send("{\"image\":\"data:image/png;base64," + ok + "\"}");
        assertThat(res.getStatus()).isEqualTo(200);
    }
}

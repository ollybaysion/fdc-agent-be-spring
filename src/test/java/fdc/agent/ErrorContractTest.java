package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 에러 응답의 상태코드·본문 계약(API.md §에러 형식, #45).
 *
 * <p>여기 걸린 것은 전부 <b>호출자 잘못</b>이다 — 서버 고장(5xx)으로 보고되면 안 된다.
 * 예전에는 포괄 {@code Exception} 핸들러가 프레임워크 예외를 삼켜 405·415 가 500 으로
 * 나갔고, 그때마다 예외 하나씩 따로 건져 올리는 땜질이 쌓였다. 표를 통째로 고정해
 * 다음 프레임워크 예외에서 같은 일이 반복되면 여기서 걸리게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    void 메서드가_틀리면_405_에_method_not_allowed() throws Exception {
        MockHttpServletResponse res = perform(post("/health"));

        assertThat(res.getStatus()).isEqualTo(405);
        assertThat(code(res)).isEqualTo("method_not_allowed");
        // 무엇을 대신 써야 하는지 알려주는 것이 405 의 존재 이유다.
        assertThat(res.getHeader("Allow")).contains("GET");
    }

    @Test
    void 지원하지_않는_content_type_은_415() throws Exception {
        MockHttpServletResponse res = perform(post("/api/fdc/v1/chat")
                .contentType(MediaType.TEXT_PLAIN)
                .content("그냥 텍스트"));

        assertThat(res.getStatus()).isEqualTo(415);
        assertThat(code(res)).isEqualTo("unsupported_media_type");
    }

    @Test
    void 받을_수_없는_accept_는_406() throws Exception {
        MockHttpServletResponse res = perform(get("/api/fdc/v1/skills")
                .accept(MediaType.APPLICATION_XML));

        assertThat(res.getStatus()).isEqualTo(406);
        // 본문은 비어 있는 게 맞다 — 클라이언트가 JSON 을 못 받겠다고 한 상황이라
        // 우리 에러 형식조차 실어 보낼 수 없다. 상태코드만이 전달 수단이다.
        assertThat(res.getContentAsString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void 깨진_JSON_은_400_invalid_json() throws Exception {
        // 상태 이름 그대로 쓰면 bad_request 지만, 이 코드는 API.md 표에 이미 있다.
        MockHttpServletResponse res = perform(post("/api/fdc/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{깨짐"));

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(code(res)).isEqualTo("invalid_json");
    }

    @Test
    void 없는_경로는_404_not_found() throws Exception {
        MockHttpServletResponse res = perform(get("/그런거없음"));

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(code(res)).isEqualTo("not_found");
    }

    @Test
    void 컨트롤러_검증_에러는_그대로_유지된다() throws Exception {
        // 프레임워크 예외 처리를 바꿨다고 기존 도메인 에러 코드가 흔들리면 안 된다.
        MockHttpServletResponse res = perform(post("/api/fdc/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[]}"));

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(code(res)).isEqualTo("messages_required");
    }

    private MockHttpServletResponse perform(RequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn().getResponse();
    }

    /** 본문은 항상 {@code {error, message?}} — 형식이 깨지면 여기서 먼저 터진다. */
    private static String code(MockHttpServletResponse res) throws Exception {
        JsonNode body = JSON.readTree(res.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("error").isTextual()).isTrue();
        return body.path("error").asText();
    }
}

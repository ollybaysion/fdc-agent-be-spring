package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import fdc.agent.config.ApiException;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.OpenAiLlm;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 온프렘 GW 가 나쁘게 굴 때의 경계 — 본문이 새지 않고, 기다림에 끝이 있다.
 * 사내 GW 는 우리가 고칠 수 없으니 이 두 가지는 이쪽에서 보장해야 한다.
 */
class OpenAiLlmHardeningTest {

    /** 이 문자열이 클라이언트 응답에 나타나면 GW 본문이 샌 것이다. */
    private static final String GW_BODY = "internal-gateway-detail-should-not-leak";

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 500 + 본문 — 사내 GW 가 무엇을 실어 보낼지는 우리 소관이 아니다.
        server.createContext("/bad", exchange -> {
            byte[] bytes = ("{\"error\":\"" + GW_BODY + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        // 응답을 주지 않는 GW.
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static List<LlmMessage> ask() {
        return List.of(LlmMessage.of(Role.USER, "안녕"));
    }

    @Test
    void 게이트웨이_오류_본문은_클라이언트로_새지_않는다() {
        OpenAiLlm llm = new OpenAiLlm(baseUrl + "/bad", "k", "m");

        assertThatThrownBy(() -> llm.next(ask(), List.of()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(502);
                    assertThat(e.getMessage()).doesNotContain(GW_BODY);
                    // 무엇이 잘못됐는지는 남긴다 — 사유는 서버 로그에.
                    assertThat(e.getMessage()).contains("500");
                });
    }

    @Test
    void 응답이_없으면_타임아웃으로_끊는다() {
        // 타임아웃이 없던 동안은 이 호출이 영영 돌아오지 않았다.
        OpenAiLlm llm = new OpenAiLlm(baseUrl + "/slow", "k", "m", 1);

        assertThatThrownBy(() -> llm.next(ask(), List.of()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(504);
                    assertThat(e.getMessage()).contains("시간이 초과");
                });
    }
}

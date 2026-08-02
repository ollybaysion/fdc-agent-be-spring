package fdc.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.config.ApiException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * SSE 인렛({@code POST /chat/*})의 공용 배관 — 이벤트 직렬화·헤더·타이핑 연출.
 * 인렛은 전부 "실패는 헤더 전에 정상 HTTP 로, 성공은 token* → done" 규율을
 * 공유하므로 배관도 한 곳에 둔다.
 */
final class SseSupport {
    private SseSupport() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 스트리밍 시작 선언 — 이후로는 정상 HTTP 상태로 에러를 낼 수 없다. */
    static void sseHeaders(HttpServletResponse res) {
        res.setStatus(200);
        res.setHeader("Content-Type", "text/event-stream; charset=utf-8");
        res.setHeader("Cache-Control", "no-cache, no-transform");
        res.setHeader("Connection", "keep-alive");
        // X-Request-Id / X-Fdc-Data-Source 는 RequestIdFilter 가 이미 실었다.
    }

    static String sse(String event, Object data) {
        try {
            return "event: " + event + "\ndata: " + JSON.writeValueAsString(data) + "\n\n";
        } catch (IOException e) {
            throw new ApiException(500, "internal", "SSE 직렬화 실패: " + e.getMessage());
        }
    }

    static void write(ServletOutputStream out, String chunk) throws IOException {
        out.write(chunk.getBytes(StandardCharsets.UTF_8));
    }

    static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void writeJson(HttpServletResponse res, int status, Map<String, ?> body)
            throws IOException {
        res.setStatus(status);
        res.setHeader("Content-Type", "application/json; charset=utf-8");
        res.getOutputStream().write(JSON.writeValueAsBytes(body));
    }
}

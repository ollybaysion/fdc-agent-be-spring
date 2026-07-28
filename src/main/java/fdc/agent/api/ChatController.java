package fdc.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.FormContext;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.config.ApiException;
import fdc.agent.config.AppProps;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.ChatDonePayload;
import fdc.agent.contract.QueryScope;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/fdc/v1/chat — SSE 스트림 (token* → done | error). 에이전트
 * 실행(툴 조회 포함)은 스트리밍 시작 전에 완료
 * — 실패하면 아직 헤더를 안 보냈으므로 정상 HTTP 상태로 에러를 낼 수 있다.
 * 성공하면 최종 텍스트를 문자 단위로 흘려보내고, 표 등 구조화 데이터는
 * done 페이로드에 번들한다(FE mock 라우트와 동일 계약).
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // API.md §1 입력 한도 (서버측 재적용).
    private static final int MAX_MESSAGES = 100;
    private static final int MAX_MESSAGE_CONTENT_CHARS = 10_000;

    private static final long TOKEN_INTERVAL_MS = 15;

    /**
     * 요청 body(FE 계약 느슨하게 수용 — 알 수 없는 필드는 무시).
     *
     * <p>{@code inputs} 는 사용자가 입력 카드로 채워 되보낸 스칼라 값으로, 스킬로
     * 네임스페이스된다({@code {skill: {key: value}}}). 지금은 한 번에 한 스킬만
     * 진행하지만(단일) 스키마는 멀티-스킬 대비다.
     *
     * <p>{@code scope} 는 사용자가 질의 대상 트레이에 담은 것이다 — 이 질문이 어느
     * 설비·어느 분석을 놓고 하는 질문인지. 없으면 지금까지와 같이 대화 맥락 전체를
     * 본다.
     */
    public record ChatBody(
            List<HistoryMessage> messages,
            List<FormContext.ContextRow> context,
            FormContext.TimeRange timeRange,
            List<ChatDataSnapshot> dataSnapshots,
            Map<String, Map<String, String>> inputs,
            QueryScope scope) {
    }

    private final ChatAgent agent;
    private final AppProps props;

    public ChatController(ChatAgent agent, AppProps props) {
        this.agent = agent;
        this.props = props;
    }

    @PostMapping("/api/fdc/v1/chat")
    public void chat(@RequestBody(required = false) ChatBody body, HttpServletResponse res)
            throws IOException {
        List<HistoryMessage> messages = body != null && body.messages() != null
                ? body.messages()
                : List.of();

        if (messages.isEmpty()) {
            writeJson(res, 400, Map.of("error", "messages_required"));
            return;
        }
        if (messages.size() > MAX_MESSAGES) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "messages_too_many");
            err.put("limit", MAX_MESSAGES);
            err.put("actual", messages.size());
            writeJson(res, 400, err);
            return;
        }
        for (HistoryMessage m : messages) {
            if (m != null && m.content() != null && m.content().length() > MAX_MESSAGE_CONTENT_CHARS) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "message_content_too_long");
                err.put("limit", MAX_MESSAGE_CONTENT_CHARS);
                err.put("actual", m.content().length());
                writeJson(res, 400, err);
                return;
            }
        }

        AgentResult result;
        try {
            result = agent.run(messages, new FormContext(
                    body != null ? body.context() : null,
                    body != null ? body.timeRange() : null),
                    body != null ? body.dataSnapshots() : null,
                    body != null ? body.inputs() : null,
                    body != null ? body.scope() : null);
        } catch (Exception err) {
            log.error("chat agent error", err);
            int statusCode = err instanceof ApiException api ? api.status() : 500;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "chat_failed");
            out.put("message", props.isProd() || err.getMessage() == null
                    ? "chat failed"
                    : err.getMessage());
            writeJson(res, statusCode, out);
            return;
        }

        String messageId = "msg_" + Long.toString(System.currentTimeMillis(), 36);
        ChatDonePayload donePayload = new ChatDonePayload(
                messageId,
                result.finishReason(),
                result.tables().isEmpty() ? null : result.tables(),
                result.recommendQuestion().isEmpty() ? null : result.recommendQuestion(),
                result.dataRequests().isEmpty() ? null : result.dataRequests(),
                result.inputRequests().isEmpty() ? null : result.inputRequests());

        res.setStatus(200);
        res.setHeader("Content-Type", "text/event-stream; charset=utf-8");
        res.setHeader("Cache-Control", "no-cache, no-transform");
        res.setHeader("Connection", "keep-alive");
        // X-Request-Id / X-Fdc-Data-Source 는 RequestIdFilter 가 이미 실었다.

        ServletOutputStream out = res.getOutputStream();
        try {
            // code point 단위로 흘린다 (서로게이트 쌍 유지).
            int[] codePoints = result.text().codePoints().toArray();
            for (int cp : codePoints) {
                String ch = new String(Character.toChars(cp));
                write(out, sse("token", Map.of("content", ch)));
                res.flushBuffer();
                sleep(TOKEN_INTERVAL_MS);
            }
            write(out, sse("done", donePayload));
        } catch (Exception err) {
            log.error("chat stream error", err);
            write(out, sse("error", Map.of("message", "stream error")));
        } finally {
            res.flushBuffer();
        }
    }

    private static String sse(String event, Object data) {
        try {
            return "event: " + event + "\ndata: " + JSON.writeValueAsString(data) + "\n\n";
        } catch (IOException e) {
            throw new ApiException(500, "internal", "SSE 직렬화 실패: " + e.getMessage());
        }
    }

    private static void write(ServletOutputStream out, String chunk) throws IOException {
        out.write(chunk.getBytes(StandardCharsets.UTF_8));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeJson(HttpServletResponse res, int status, Map<String, ?> body)
            throws IOException {
        res.setStatus(status);
        res.setHeader("Content-Type", "application/json; charset=utf-8");
        res.getOutputStream().write(JSON.writeValueAsBytes(body));
    }
}

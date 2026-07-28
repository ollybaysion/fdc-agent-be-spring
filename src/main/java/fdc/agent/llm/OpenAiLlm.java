package fdc.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.config.ApiException;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmToolSpec;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.util.Trace;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI 호환 온프렘 GW 어댑터 (LLM_BASE_URL 설정 시). 사내는
 * LLM_BASE_URL/KEY/MODEL 만 채우면 된다. non-stream 으로 한 턴을
 * 받고, 라우트가 토큰 스트리밍을 흉내낸다(단순·견고).
 *
 * <p>프로세스 밖으로 나가는 유일한 지점이라 세 가지를 여기서 책임진다:
 * <b>기다림에 끝이 있고</b>(연결·응답 타임아웃), <b>GW 응답 본문이 클라이언트로
 * 새지 않고</b>(사유는 로그로, 응답에는 상태만), <b>한 호출이 무엇을 썼는지 남는다</b>
 * (usage·소요 시간 로그).
 */
public class OpenAiLlm implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlm.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 연결 자체가 안 서면 오래 붙들 이유가 없다. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** 로그에 남길 GW 오류 본문 길이 — 진단엔 충분하고 로그를 덮지는 않는 선. */
    private static final int LOGGED_BODY_CHARS = 500;

    private final HttpClient http;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public OpenAiLlm(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, DEFAULT_TIMEOUT_SECONDS);
    }

    public OpenAiLlm(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        this.apiKey = apiKey;
        this.model = model != null && !model.isEmpty() ? model : "default";
        this.timeout = Duration.ofSeconds(
                timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public LlmTurn next(List<LlmMessage> messages, List<LlmToolSpec> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(OpenAiLlm::toOpenAiMessage).toList());
        // tools 가 비면 tools/tool_choice 를 아예 생략한다 — 빈 tools 에
        // tool_choice:"auto" 를 함께 보내면 일부 GW(예: Anthropic OpenAI 호환)
        // 가 400 으로 거부한다(후속 질문 생성처럼 툴 없는 호출에서 발생).
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream()
                    .map(t -> Map.of("type", "function", "function", t))
                    .toList());
            body.put("tool_choice", "auto");
        }
        body.put("temperature", 0);
        body.put("stream", false);

        long startedAt = System.nanoTime();
        HttpResponse<String> res;
        try {
            // 전선에 나가는 JSON 그대로 — tools[].function 의 설명·스키마가 실렸는지는
            // 여기서만 확정된다. Authorization 헤더는 찍지 않는다.
            String wire = JSON.writeValueAsString(body);
            Trace.raw("BE→LLM HTTP POST " + endpoint, wire);
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(wire, StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isEmpty()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            log.error("LLM 응답 {}초 초과 (endpoint={})", timeout.toSeconds(), endpoint);
            throw new ApiException(504, "error", "LLM 응답 시간이 초과되었습니다.");
        } catch (IOException e) {
            log.error("LLM 요청 실패 (endpoint={}): {}", endpoint, e.toString());
            throw new ApiException(502, "error", "LLM 게이트웨이에 연결하지 못했습니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "error", "LLM 요청이 중단되었습니다.");
        }

        Trace.raw("LLM→BE HTTP " + res.statusCode(), res.body());

        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            // GW 본문은 로그로만 — 무엇이 실릴지는 우리 소관이 아니라서,
            // 그대로 클라이언트로 되돌리면 그게 곧 반출이 된다.
            log.error("LLM {} 응답 (endpoint={}): {}", res.statusCode(), endpoint, truncate(res.body()));
            throw new ApiException(502, "error", "LLM 게이트웨이 오류(HTTP " + res.statusCode() + ").");
        }

        JsonNode root;
        try {
            root = JSON.readTree(res.body());
        } catch (IOException e) {
            log.error("LLM 응답 JSON 파싱 실패 (endpoint={}): {}", endpoint, truncate(res.body()));
            throw new ApiException(502, "error", "LLM 응답을 해석하지 못했습니다.");
        }
        logUsage(root, tools.size(), System.nanoTime() - startedAt);

        JsonNode msg = root.path("choices").path(0).path("message");
        JsonNode toolCalls = msg.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            List<LlmToolCall> calls = new ArrayList<>();
            for (JsonNode tc : toolCalls) {
                calls.add(new LlmToolCall(
                        tc.path("id").asText(),
                        tc.path("function").path("name").asText(),
                        safeParseArgs(tc.path("function").path("arguments").asText())));
            }
            return new LlmTurn.ToolCalls(calls);
        }
        String content = msg.path("content").isNull() || msg.path("content").isMissingNode()
                ? ""
                : msg.path("content").asText();
        return new LlmTurn.Final(content);
    }

    /**
     * 호출 한 번의 값 — 모델·툴 수·소요 시간·토큰. usage 는 그동안 파싱조차 하지 않고
     * 버려서, "이 대화가 무엇을 얼마나 썼나"를 서버 로그로 답할 수 없었다.
     * GW 가 usage 를 안 주면 토큰 자리는 비운다.
     */
    private void logUsage(JsonNode root, int toolCount, long elapsedNanos) {
        long ms = elapsedNanos / 1_000_000;
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            log.info("llm 호출: model={} tools={} {}ms (usage 없음)", model, toolCount, ms);
            return;
        }
        log.info("llm 호출: model={} tools={} {}ms tokens(prompt/completion/total)={}/{}/{}",
                model, toolCount, ms,
                usage.path("prompt_tokens").asInt(-1),
                usage.path("completion_tokens").asInt(-1),
                usage.path("total_tokens").asInt(-1));
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= LOGGED_BODY_CHARS
                ? body
                : body.substring(0, LOGGED_BODY_CHARS) + "…(총 " + body.length() + "자)";
    }

    /** LlmMessage → OpenAI wire 형식(assistant.tool_calls / tool.tool_call_id). */
    private static Map<String, Object> toOpenAiMessage(LlmMessage m) {
        if (m.role() == Role.TOOL) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("role", "tool");
            out.put("tool_call_id", m.toolCallId());
            out.put("content", m.content() != null ? m.content() : "");
            return out;
        }
        if (m.role() == Role.ASSISTANT && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("role", "assistant");
            out.put("content", m.content() != null ? m.content() : "");
            out.put("tool_calls", m.toolCalls().stream().map(tc -> {
                String args;
                try {
                    args = JSON.writeValueAsString(tc.arguments());
                } catch (IOException e) {
                    args = "{}";
                }
                return Map.of(
                        "id", tc.id(),
                        "type", "function",
                        "function", Map.of("name", tc.name(), "arguments", args));
            }).toList());
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", m.role().wire());
        out.put("content", m.content() != null ? m.content() : "");
        return out;
    }

    private static Map<String, Object> safeParseArgs(String raw) {
        try {
            JsonNode v = JSON.readTree(raw);
            if (v != null && v.isObject()) {
                return JSON.convertValue(v, JSON.getTypeFactory()
                        .constructMapType(LinkedHashMap.class, String.class, Object.class));
            }
            return Map.of();
        } catch (IOException e) {
            return Map.of();
        }
    }
}

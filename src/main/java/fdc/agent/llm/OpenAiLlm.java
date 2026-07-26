package fdc.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.config.ApiException;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmToolSpec;
import fdc.agent.llm.LlmTypes.LlmTurn;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 호환 온프렘 GW 어댑터 (LLM_BASE_URL 설정 시). 사내는
 * LLM_BASE_URL/KEY/MODEL 만 채우면 된다. non-stream 으로 한 턴을
 * 받고, 라우트가 토큰 스트리밍을 흉내낸다(단순·견고).
 */
public class OpenAiLlm implements LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiLlm(String baseUrl, String apiKey, String model) {
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        this.apiKey = apiKey;
        this.model = model != null && !model.isEmpty() ? model : "default";
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

        HttpResponse<String> res;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isEmpty()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(502, "error", "LLM request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "error", "LLM request interrupted");
        }

        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            String bodyText = res.body() == null ? "" : res.body();
            throw new ApiException(502, "error",
                    "LLM " + res.statusCode() + ": " + bodyText.substring(0, Math.min(200, bodyText.length())));
        }

        JsonNode msg;
        try {
            msg = JSON.readTree(res.body()).path("choices").path(0).path("message");
        } catch (IOException e) {
            throw new ApiException(502, "error", "LLM invalid JSON: " + e.getMessage());
        }

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

    /** LlmMessage → OpenAI wire 형식(assistant.tool_calls / tool.tool_call_id). */
    private static Map<String, Object> toOpenAiMessage(LlmMessage m) {
        if ("tool".equals(m.role())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("role", "tool");
            out.put("tool_call_id", m.toolCallId());
            out.put("content", m.content() != null ? m.content() : "");
            return out;
        }
        if ("assistant".equals(m.role()) && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
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
        out.put("role", m.role());
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

package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.FinishReason;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmToolCall;
import fdc.agent.llm.LlmTypes.LlmToolSpec;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.llm.OpenAiLlm;
import fdc.agent.skills.SkillRegistry;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 온프렘 OpenAI 호환 GW 연결 경로를 사내 이전 전에 검증. 가짜 서버로 (a)
 * OpenAI 형식 요청, (b) tool_calls / 최종 content 파싱, (c) 에이전트 루프
 * e2e(fixture 데이터)를 확인한다.
 */
class OpenAiLlmTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static HttpServer server;
    private static String baseUrl;
    private static final List<JsonNode> sentBodies = new ArrayList<>();

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            synchronized (sentBodies) {
                sentBodies.add(body);
            }
            // tool 결과가 대화에 있으면 최종 답, 아니면 툴 호출 — 실 LLM 의
            // 2턴 tool-calling 흐름을 모사.
            boolean hasToolResult = false;
            for (JsonNode m : body.path("messages")) {
                if ("tool".equals(m.path("role").asText())) {
                    hasToolResult = true;
                }
            }
            String response = hasToolResult
                    ? """
                    {"choices":[{"message":{"role":"assistant",
                      "content":"센서 S-0004 를 확인했습니다. 소속 설비·최근 이벤트는 표를 참고하세요."}}]}
                    """
                    : """
                    {"choices":[{"message":{"role":"assistant","content":null,
                      "tool_calls":[{"id":"call_x1","type":"function",
                        "function":{"name":"fdc_explain_sensor","arguments":"{\\"snsr_id\\":\\"S-0004\\"}"}}]}}]}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static JsonNode lastSent() {
        synchronized (sentBodies) {
            return sentBodies.get(sentBodies.size() - 1);
        }
    }

    private static LlmToolSpec explainSensorSpec() {
        return new LlmToolSpec("fdc_explain_sensor", "센서 설명", Map.of(
                "type", "object",
                "properties", Map.of("snsr_id", Map.of("type", "string")),
                "required", List.of("snsr_id")));
    }

    @Test
    void 요청을_OpenAI_형식으로_보내고_tool_calls_응답을_파싱한다() {
        OpenAiLlm llm = new OpenAiLlm(baseUrl, "test-key", "onprem-x");
        LlmTurn turn = llm.next(
                List.of(LlmMessage.of(Role.USER, "S-0004 센서 설명")),
                List.of(explainSensorSpec()));

        assertThat(turn).isInstanceOf(LlmTurn.ToolCalls.class);
        LlmTurn.ToolCalls calls = (LlmTurn.ToolCalls) turn;
        assertThat(calls.toolCalls().get(0).name()).isEqualTo("fdc_explain_sensor");
        assertThat(calls.toolCalls().get(0).arguments()).isEqualTo(Map.of("snsr_id", "S-0004"));

        // 나간 요청이 OpenAI 형식인지(model/tool_choice/tools[].function).
        JsonNode sent = lastSent();
        assertThat(sent.path("model").asText()).isEqualTo("onprem-x");
        assertThat(sent.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(sent.path("tools").get(0).path("type").asText()).isEqualTo("function");
        assertThat(sent.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("fdc_explain_sensor");
    }

    @Test
    void tool_결과가_포함된_대화엔_최종_content를_돌려준다() {
        OpenAiLlm llm = new OpenAiLlm(baseUrl, "test-key", "onprem-x");
        LlmTurn turn = llm.next(List.of(
                LlmMessage.of(Role.USER, "S-0004 센서 설명"),
                LlmMessage.assistantToolCalls(List.of(
                        new LlmToolCall("call_x1", "fdc_explain_sensor", Map.of("snsr_id", "S-0004")))),
                LlmMessage.toolResult("call_x1", "fdc_explain_sensor", "센서 S-0004 요약")),
                List.of());
        assertThat(turn).isInstanceOf(LlmTurn.Final.class);
        assertThat(((LlmTurn.Final) turn).content()).contains("S-0004");
    }

    @Test
    void tools가_비면_tools_tool_choice를_요청에서_생략한다() {
        OpenAiLlm llm = new OpenAiLlm(baseUrl, "test-key", "onprem-x");
        llm.next(List.of(LlmMessage.of(Role.USER, "후속 질문 3개 제안")), List.of());
        JsonNode sent = lastSent();
        assertThat(sent.has("tools")).isFalse();
        assertThat(sent.has("tool_choice")).isFalse();
        assertThat(sent.path("model").asText()).isEqualTo("onprem-x");
    }

    @Test
    void 에이전트_루프가_실_openai_어댑터로_툴_호출과_표_생성까지_간다() {
        OpenAiLlm llm = new OpenAiLlm(baseUrl, "test-key", "onprem-x");
        ChatAgent agent = new ChatAgent(llm, SkillRegistry.FIXTURE_SKILL_QUERY);
        AgentResult result = agent.run(
                List.of(new HistoryMessage(Role.USER, "S-0004 센서 설명해줘")), null);

        assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.text()).contains("S-0004");

        // 스킬 조달이 낸 표가 그대로 실려 온다(센서 → 소속 설비 → 최근 이벤트).
        List<String> titles = result.tables().stream().map(t -> t.title()).toList();
        assertThat(titles).contains("sensor_row", "equipment_row");
        assertThat(result.tables().get(0).rows().get(0)).containsEntry("SNSR_ID", "S-0004");
    }

    @Test
    void 이미지_없는_메시지는_content가_문자열_그대로다() {
        OpenAiLlm llm = new OpenAiLlm(baseUrl, "test-key", "onprem-x");
        llm.next(List.of(LlmMessage.of(Role.USER, "그냥 텍스트")), List.of());

        JsonNode content = lastSent().path("messages").get(0).path("content");
        assertThat(content.isTextual()).isTrue();
        assertThat(content.asText()).isEqualTo("그냥 텍스트");
    }

    @Test
    void 이미지_있는_메시지는_content가_파트_배열이다() {
        OpenAiLlm llm = new OpenAiLlm(baseUrl, "test-key", "onprem-x");
        llm.next(List.of(LlmMessage.withImages(Role.USER, "이 화면 뭐야?",
                List.of("data:image/png;base64,AAAA"))), List.of());

        JsonNode content = lastSent().path("messages").get(0).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("이 화면 뭐야?");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64,AAAA");
    }
}

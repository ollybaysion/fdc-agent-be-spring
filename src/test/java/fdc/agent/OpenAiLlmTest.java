package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fdc.agent.chat.ChatAgent;
import fdc.agent.chat.ChatAgent.AgentResult;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.data.fixtures.FixtureRepo;
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
 * Node 판 test/openai-llm.test.ts 포팅 — 온프렘 OpenAI 호환 GW 연결 경로를
 * 사내 이전 전에 검증. 가짜 서버로 (a) OpenAI 형식 요청, (b) tool_calls /
 * 최종 content 파싱, (c) 에이전트 루프 e2e(fixture 데이터)를 확인한다.
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
                      "content":"설비 ETCH-01 상세를 확인했습니다. 챔버·센서 값은 표를 참고하세요."}}]}
                    """
                    : """
                    {"choices":[{"message":{"role":"assistant","content":null,
                      "tool_calls":[{"id":"call_x1","type":"function",
                        "function":{"name":"get_equipment_detail","arguments":"{\\"id\\":\\"ETCH-01\\"}"}}]}}]}
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

    private static LlmToolSpec detailToolSpec() {
        return new LlmToolSpec("get_equipment_detail", "설비 상세", Map.of(
                "type", "object",
                "properties", Map.of("id", Map.of("type", "string")),
                "required", List.of("id")));
    }

    @Test
    void 요청을_OpenAI_형식으로_보내고_tool_calls_응답을_파싱한다() {
        OpenAiLlm llm = new OpenAiLlm(baseUrl, "test-key", "onprem-x");
        LlmTurn turn = llm.next(
                List.of(LlmMessage.of("user", "ETCH-01 설비 정보")),
                List.of(detailToolSpec()));

        assertThat(turn).isInstanceOf(LlmTurn.ToolCalls.class);
        LlmTurn.ToolCalls calls = (LlmTurn.ToolCalls) turn;
        assertThat(calls.toolCalls().get(0).name()).isEqualTo("get_equipment_detail");
        assertThat(calls.toolCalls().get(0).arguments()).isEqualTo(Map.of("id", "ETCH-01"));

        // 나간 요청이 OpenAI 형식인지(model/tool_choice/tools[].function).
        JsonNode sent = lastSent();
        assertThat(sent.path("model").asText()).isEqualTo("onprem-x");
        assertThat(sent.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(sent.path("tools").get(0).path("type").asText()).isEqualTo("function");
        assertThat(sent.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("get_equipment_detail");
    }

    @Test
    void tool_결과가_포함된_대화엔_최종_content를_돌려준다() {
        OpenAiLlm llm = new OpenAiLlm(baseUrl, "test-key", "onprem-x");
        LlmTurn turn = llm.next(List.of(
                LlmMessage.of("user", "ETCH-01 설비 정보"),
                LlmMessage.assistantToolCalls(List.of(
                        new LlmToolCall("call_x1", "get_equipment_detail", Map.of("id", "ETCH-01")))),
                LlmMessage.toolResult("call_x1", "get_equipment_detail", "설비 ETCH-01 요약")),
                List.of());
        assertThat(turn).isInstanceOf(LlmTurn.Final.class);
        assertThat(((LlmTurn.Final) turn).content()).contains("ETCH-01");
    }

    @Test
    void tools가_비면_tools_tool_choice를_요청에서_생략한다() {
        OpenAiLlm llm = new OpenAiLlm(baseUrl, "test-key", "onprem-x");
        llm.next(List.of(LlmMessage.of("user", "후속 질문 3개 제안")), List.of());
        JsonNode sent = lastSent();
        assertThat(sent.has("tools")).isFalse();
        assertThat(sent.has("tool_choice")).isFalse();
        assertThat(sent.path("model").asText()).isEqualTo("onprem-x");
    }

    @Test
    void 에이전트_루프가_실_openai_어댑터로_툴_호출과_표_생성까지_간다() {
        OpenAiLlm llm = new OpenAiLlm(baseUrl, "test-key", "onprem-x");
        ChatAgent agent = new ChatAgent(llm, new FixtureRepo(), SkillRegistry.FIXTURE_SKILL_QUERY);
        AgentResult result = agent.run(
                List.of(new HistoryMessage("user", "ETCH-01 설비 정보 보여줘")), null);

        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.text()).contains("ETCH-01");

        List<String> titles = result.tables().stream().map(t -> t.title()).toList();
        assertThat(titles).containsExactly("설비 정보", "챔버 정보", "센서 정보");
        assertThat(result.tables().get(0).rows().get(0).get("ID")).isEqualTo("ETCH-01");
    }
}

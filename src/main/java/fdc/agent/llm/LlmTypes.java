package fdc.agent.llm;

import java.util.List;
import java.util.Map;

/**
 * 온프렘 LLM 추상화(seam, Node 판 llm/types.ts 대응). mock ↔ openai 를
 * 갈아끼우고 에이전트 루프는 이 인터페이스만 안다. 스트리밍은 라우트가
 * 최종 텍스트를 문자 단위로 쪼개 흉내낸다 — 클라이언트는 "한 턴"을 완결해
 * 돌려주면 되므로 단순·견고.
 */
public final class LlmTypes {
    private LlmTypes() {
    }

    /** OpenAI 호환 대화 메시지(툴 호출/결과 포함). content 는 툴만 호출 시 null. */
    public record LlmMessage(
            String role,
            String content,
            List<LlmToolCall> toolCalls,
            String toolCallId,
            String name) {

        public static LlmMessage of(String role, String content) {
            return new LlmMessage(role, content, null, null, null);
        }

        public static LlmMessage assistantToolCalls(List<LlmToolCall> toolCalls) {
            return new LlmMessage("assistant", null, toolCalls, null, null);
        }

        public static LlmMessage toolResult(String toolCallId, String name, String content) {
            return new LlmMessage("tool", content, null, toolCallId, name);
        }
    }

    /** LLM 이 요청한 툴 호출(인자는 파싱된 객체). */
    public record LlmToolCall(String id, String name, Map<String, Object> arguments) {
    }

    /** 에이전트가 LLM 에 넘기는 툴 정의. parameters = OpenAI function JSON Schema. */
    public record LlmToolSpec(String name, String description, Map<String, Object> parameters) {
    }

    /** 한 턴의 결과: 툴을 부르거나(ToolCalls), 최종 답을 내거나(Final). */
    public sealed interface LlmTurn {
        record ToolCalls(List<LlmToolCall> toolCalls) implements LlmTurn {
        }

        record Final(String content) implements LlmTurn {
        }
    }

    @FunctionalInterface
    public interface LlmClient {
        LlmTurn next(List<LlmMessage> messages, List<LlmToolSpec> tools);
    }
}

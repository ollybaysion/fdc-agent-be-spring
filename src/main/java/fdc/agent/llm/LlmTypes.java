package fdc.agent.llm;

import fdc.agent.contract.Role;
import java.util.List;
import java.util.Map;

/**
 * 온프렘 LLM 추상화(seam). mock ↔ openai 를
 * 갈아끼우고 에이전트 루프는 이 인터페이스만 안다. 스트리밍은 라우트가
 * 최종 텍스트를 문자 단위로 쪼개 흉내낸다 — 클라이언트는 "한 턴"을 완결해
 * 돌려주면 되므로 단순·견고.
 */
public final class LlmTypes {
    private LlmTypes() {
    }

    /**
     * OpenAI 호환 대화 메시지(툴 호출/결과 포함). content 는 툴만 호출 시 null.
     * {@code images}(data URL 등)가 실리면 {@code OpenAiLlm} 이 content 를 파트
     * 배열로 바꿔 보낸다(#63 캡처 분류) — 없으면 기존 문자열 경로 그대로다.
     */
    public record LlmMessage(
            Role role,
            String content,
            List<LlmToolCall> toolCalls,
            String toolCallId,
            String name,
            List<String> images) {

        public static LlmMessage of(Role role, String content) {
            return new LlmMessage(role, content, null, null, null, null);
        }

        public static LlmMessage assistantToolCalls(List<LlmToolCall> toolCalls) {
            return new LlmMessage(Role.ASSISTANT, null, toolCalls, null, null, null);
        }

        public static LlmMessage toolResult(String toolCallId, String name, String content) {
            return new LlmMessage(Role.TOOL, content, null, toolCallId, name, null);
        }

        /** 이미지 파트를 실은 메시지 — vision 호출이 필요할 때(#63). */
        public static LlmMessage withImages(Role role, String content, List<String> images) {
            return new LlmMessage(role, content, null, null, null, images);
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

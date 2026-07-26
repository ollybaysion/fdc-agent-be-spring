package fdc.agent.chat;

import fdc.agent.contract.ChatTable;
import java.util.List;
import java.util.Map;

/**
 * 에이전트 툴. LlmToolSpec(LLM 이 보는 정의) +
 * execute(실제 조회). 반환은 LLM 에 되먹일 summary + done 에 실을 tables.
 *
 * 이 모양이 곧 forge-domain-skill 의 spec 이 컴파일되면 나올 결과물 —
 * SkillLoader 가 spec.json 을 읽어 이 형태로 추가하면 에이전트/라우트
 * 무수정으로 새 도메인 스킬이 붙는다.
 */
public record AgentTool(
        String name,
        String description,
        Map<String, Object> parameters,
        ToolFn execute) {

    /** LLM 에 되먹일 요약(자연어) + done 페이로드에 실을 표(옵션, null 허용). */
    public record ToolResult(String summary, List<ChatTable> tables) {
        public static ToolResult of(String summary) {
            return new ToolResult(summary, null);
        }
    }

    @FunctionalInterface
    public interface ToolFn {
        ToolResult run(Map<String, Object> args);
    }
}

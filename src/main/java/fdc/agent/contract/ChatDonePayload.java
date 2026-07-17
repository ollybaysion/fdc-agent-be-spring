package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * SSE `done` 이벤트 페이로드. 텍스트는 token 스트림으로 이미 전달되고,
 * 표/추천질문 등 구조화 데이터는 여기에 번들(FE mock 라우트와 동일 형태).
 * tables/recommendQuestion 은 비면 null 로 두어 JSON 에서 생략한다
 * (Node 판 라우트의 조건부 spread 대응).
 */
public record ChatDonePayload(
        String messageId,
        String finishReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<ChatTable> tables,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> recommendQuestion) {
}

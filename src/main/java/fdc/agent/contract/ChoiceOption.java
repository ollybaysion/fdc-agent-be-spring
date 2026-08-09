package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@link ChoiceRequest} 의 선택지 한 칸. {@code label} 이 화면 카드에 뜨는 문구이자
 * 회신 문장("선택 — {label}")에 실리는 값이다. {@code description} 은 선택 — 없으면
 * JSON 에서 생략한다.
 */
public record ChoiceOption(
        String label,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description) {
}

package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 답이 가리키는 바깥 문서 — 사내 위키·티켓·규격서.
 *
 * <p>{@code label} 이 비면 FE 가 주소의 호스트로 대신한다 — 이름 없는 카드는 만들지
 * 않는다.
 */
public record ChatLink(
        String label,
        String url,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description) {
}

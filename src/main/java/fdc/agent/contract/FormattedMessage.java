package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 붙여넣은 메시지의 포맷팅 결과 — {@code chat/data} 메시지 판정(#64 MVP)의 응답.
 *
 * <p>MVP 에서 변환·요약·추출은 전부 LLM 1회가 한다(설계 결정 8: 편의성 기능이라
 * 오차 허용, FE 카드의 원문 접힘이 진실원 안전망). 그래서 {@code json} 을 포함한
 * 모든 값이 LLM 산출물이고, 결정론 파서·warnings 검증은 고도화(#66)로 이월됐다.
 *
 * <p>{@code json} 은 문자열이 아니라 객체다 — pretty-print 는 FE 소유.
 * {@code eqpId} 는 FE 가 설비 카드 배치에 쓰고, 없으면 미분류로 흘러간다.
 */
public record FormattedMessage(
        Object json,
        @JsonInclude(JsonInclude.Include.NON_NULL) String comment,
        @JsonInclude(JsonInclude.Include.NON_NULL) String eqpId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String className,
        @JsonInclude(JsonInclude.Include.NON_NULL) String docId) {
}

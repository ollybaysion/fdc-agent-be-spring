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
 *
 * <p>{@code raw} 는 이 한 건의 원문 조각이다 — 다건에서는 FE 가 무엇을 보냈는지로
 * 되짚을 수 없다(자르는 건 BE 다). {@code json} 이 없으면 <b>변환 실패</b>고, 그때도
 * 원문은 실려 온다: 100건 중 몇 건이 실패했다고 그 건이 화면에서 사라지면 사용자는
 * 무엇이 빠졌는지 알 길이 없다.
 *
 * <p>{@code title}·{@code occurredAt} 은 <b>다건</b>을 위한 필드다(#64 다중 메시지).
 * 한 건일 때는 없어도 화면이 성립하지만, 수십 건이 목록에 쌓이면 서로를 구별할
 * 이름과 줄 세울 시각이 없으면 고를 수가 없다. {@code occurredAt} 은 등록 시각이
 * 아니라 <b>메시지 안에 찍힌 발생 시각</b>이고, 같은 초에 여러 건이 들어오는 데이터라
 * 소수초까지 원문 정밀도를 그대로 싣는다(FE 정렬이 그 자릿수에 의존한다).
 */
public record FormattedMessage(
        @JsonInclude(JsonInclude.Include.NON_NULL) Object json,
        @JsonInclude(JsonInclude.Include.NON_NULL) String comment,
        @JsonInclude(JsonInclude.Include.NON_NULL) String eqpId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String className,
        @JsonInclude(JsonInclude.Include.NON_NULL) String title,
        @JsonInclude(JsonInclude.Include.NON_NULL) String occurredAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String raw,
        @JsonInclude(JsonInclude.Include.NON_NULL) String docId) {
}

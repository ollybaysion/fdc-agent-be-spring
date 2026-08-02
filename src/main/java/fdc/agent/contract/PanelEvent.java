package fdc.agent.contract;

/**
 * 데이터 패널에서 방금 일어난 액션 하나 — panel-judge 판정 요청의 방아쇠
 * (demo-fe 짝 계약, #38). {@code type} 은 FE 가 정의하는 액션 이름
 * (등록/수정/휴지통 이동 등)이고 BE 판정은 여기에 결합하지 않는다 —
 * 판정 입력은 어디까지나 스냅샷 집합과 run 선언이며, 이벤트는 <b>서술 전이
 * 감지</b>(이 액션이 절차를 완성시켰나)에만 쓰인다.
 *
 * <p>계약은 느슨하게 수용한다 — 빠진 필드는 null.
 */
public record PanelEvent(String type, String queryKey) {
}

package fdc.agent.contract;

import java.util.List;

/**
 * BE 가 "빠진 값의 후보를 2~10개로 좁혔다"를 알리는 선택 요청. done 페이로드의
 * {@code choiceRequests} 로 나가면 FE 가 카드로 렌더한다 — 사용자의 실제 클릭이
 * {@code "선택 — {label}"} 형태의 user 메시지로 대화에 복귀한다({@link InputRequest}
 * 와 달리 구조화 회신 필드는 없다).
 *
 * <p>{@code multiSelect} 는 카드 레벨 — true 면 여러 항목을 고를 수 있고 "전부"
 * 숏컷이 뜬다.
 */
public record ChoiceRequest(
        String question,
        List<ChoiceOption> options,
        boolean multiSelect) {
}

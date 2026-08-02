package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * BE 가 "이 질문에 답하려면 이 데이터가 필요한데 지금 조회할 수 없다"를 알리는 요청
 * (demo-fe {@code DataRequest} 계약과 1:1). done 페이로드의 {@code dataRequests} 로
 * 나가면 FE 가 요청 카드로 렌더한다 — 사용자가 SQL 을 실행해 결과를 붙여넣으면 그
 * 스냅샷이 {@code queryKey} 로 등록돼, 다음 요청에서 충족 여부가 판정된다.
 *
 * <p>{@code sql}·{@code columns} 는 선택 — 없으면 JSON 에서 생략한다.
 *
 * <p>{@code run} 은 이 카드가 속한 절차 실행(스킬+원문 인자). FE 가 카드를 설비→분석
 * 계층에 앉히는 근거다 — {@code queryKey} 는 손실 인코딩이라 여기서 역파싱하면 안
 * 되고, 소속은 이 필드가 정본이다.
 */
public record DataRequest(
        String queryKey,
        String label,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sql,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> columns,
        @JsonInclude(JsonInclude.Include.NON_NULL) RunDecl run) {
}

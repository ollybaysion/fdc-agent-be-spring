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
 */
public record DataRequest(
        String queryKey,
        String label,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sql,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> columns) {
}

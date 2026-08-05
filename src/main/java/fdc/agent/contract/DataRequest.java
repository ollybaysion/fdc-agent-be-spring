package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * BE 가 "이 질문에 답하려면 이 데이터가 필요한데 지금 조회할 수 없다"를 알리는 요청
 * (demo-fe {@code DataRequest} 계약과 1:1). 사용자가 SQL 을 실행해 결과를 붙여넣으면
 * 그 스냅샷이 {@code queryKey} 로 등록돼, 다음 요청에서 충족 여부가 판정된다.
 *
 * <p>{@code /chat} 에서는 이 응답에서 <b>새로 생긴 요청</b>만 나가고,
 * {@code /chat/data} 에서는 <b>원장</b>으로 나간다 — 그 절차의 조달 전량이 상태를
 * 달고 매번 전부 실리고 FE 는 replace 한다. 요청을 이벤트로 흘리면 멱등성이 규칙이
 * 되지만, 상태 전량으로 실으면 구조가 된다.
 *
 * <p>{@code sql} 은 {@link RequestState#READY} 일 때만 있다. 나머지 상태에서는 아직
 * (또는 영영) 완성할 수 없고, 그때 화면이 말할 수 있는 것은 {@code label}(이 조달이
 * 채우는 need 의 what)과 {@code blocked}(왜 못 여는가)다.
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
        @JsonInclude(JsonInclude.Include.NON_NULL) RunDecl run,
        RequestState state,
        /** 못 여는 사유 — {@link RequestState#READY}·{@link RequestState#ARRIVED} 면 null. */
        @JsonInclude(JsonInclude.Include.NON_NULL) String blocked,
        /** 이 조달이 채우는 need 의 id 들 — 원장 줄을 need 로 되짚는 근거. */
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> needs) {

    /** 채팅 경로의 요청 — 나가는 것은 언제나 SQL 이 완성된 것뿐이다. */
    public DataRequest(String queryKey, String label, String sql, List<String> columns, RunDecl run) {
        this(queryKey, label, sql, columns, run, RequestState.READY, null, List.of());
    }
}

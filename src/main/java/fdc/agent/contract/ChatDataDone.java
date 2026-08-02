package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/fdc/v1/chat/data} SSE {@code done} 페이로드 — 선언적 판정
 * 결과(#38). 서술이 있으면 그 앞에 token 스트림이 흐르고, 카드만이면 done 만 온다.
 *
 * <p>{@code openRequests} 는 <b>지금 열려 있어야 할 카드 전체</b>다 — 이번에 새로
 * 생긴 것만이 아니라. FE 는 이 목록으로 리컨사일한다(회귀 포함): 카드 진실원을
 * 서버 판정 하나로 두기 위한 선언적 계약이다.
 *
 * <p>{@code eventId}/{@code revision} 은 요청 echo — 재시도·토글 왕복에서 FE 가
 * 낡은 응답을 버리는 근거다(T7). {@code poolRev} 는 스킬 풀 지문 — 풀이 시변이라
 * (akg 주기 refresh) 같은 body 가 다른 판정일 수 있음을 FE 가 감지한다(T14).
 *
 * <p>{@code needsRows} 가 오면 그 스냅샷의 rows 를 실어 재호출하라는 뜻이다(T16).
 * {@code narratedRun} 은 이 응답의 token 스트림이 어느 절차의 종결 서술인지다.
 */
public record ChatDataDone(
        String messageId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String eventId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer revision,
        String poolRev,
        List<DataRequest> openRequests,
        List<RunProgress> runsProgress,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> terminalRuns,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> needsRows,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Map<String, String>> picks,
        @JsonInclude(JsonInclude.Include.NON_NULL) String narratedRun) {
}

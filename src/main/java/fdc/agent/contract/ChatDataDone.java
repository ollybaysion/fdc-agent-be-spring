package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * {@code POST /api/fdc/v1/chat/data} SSE {@code done} 페이로드 — 선언적 판정
 * 결과(#38). 서술이 있으면 그 앞에 token 스트림이 흐르고, 아니면 done 만 온다.
 * 요청 카드는 여기 없다 — 카드 배치·SQL 완성은 FE 가 카탈로그(binds 포함)로
 * 로컬 판정한다(demo-fe dataList). 이 페이로드는 BE 인지의 선언이다.
 *
 * <p>{@code eventId}/{@code revision} 은 요청 echo — 재시도·토글 왕복에서 FE 가
 * 낡은 응답을 버리는 근거다(T7). {@code poolRev} 는 스킬 풀 지문 — 풀이 시변이라
 * (akg 주기 refresh) 같은 body 가 다른 판정일 수 있음을 FE 가 감지한다(T14).
 * {@code narratedRun} 은 이 응답의 token 스트림이 어느 절차의 종결 서술인지다.
 */
public record ChatDataDone(
        String messageId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String eventId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer revision,
        String poolRev,
        List<RunProgress> runsProgress,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> terminalRuns,
        @JsonInclude(JsonInclude.Include.NON_NULL) String narratedRun) {
}

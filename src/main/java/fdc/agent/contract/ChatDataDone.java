package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * {@code POST /api/fdc/v1/chat/data} SSE {@code done} 페이로드 — 선언적 판정
 * 결과(#38). 서술이 있으면 그 앞에 token 스트림이 흐르고, 아니면 done 만 온다.
 *
 * <p>{@code dataRequests} 는 <b>조달 원장</b>이다 — 판정에 든 절차들의 조달 전량이
 * 상태({@link RequestState})를 달고 매번 전부 실린다. FE 는 이것을 replace 하고
 * 상태대로 그린다: 무엇을 보일지·감출지 판단하지 않는다. 스킬을 읽고 카드를
 * 배치하는 일은 BE 소관이고 화면은 송출이다.
 *
 * <p>{@code eventId}/{@code revision} 은 요청 echo — 재시도·토글 왕복에서 FE 가
 * 낡은 응답을 버리는 근거다(T7). {@code poolRev} 는 스킬 풀 지문 — 풀이 시변이라
 * (akg 주기 refresh) 같은 body 가 다른 판정일 수 있음을 FE 가 감지한다(T14).
 * {@code narratedRun} 은 이 응답의 token 스트림이 어느 절차의 종결 서술인지다.
 *
 * <p>{@code formattedMessage} 는 메시지 판정 왕복({@code pasted} 실림, #64)에만
 * 실린다 — 있으면 FE 는 메시지 카드를 세우고, 없으면 로컬 표 파싱으로 폴백한다.
 */
public record ChatDataDone(
        String messageId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String eventId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer revision,
        @JsonInclude(JsonInclude.Include.NON_NULL) String poolRev,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<DataRequest> dataRequests,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RunProgress> runsProgress,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> terminalRuns,
        @JsonInclude(JsonInclude.Include.NON_NULL) String narratedRun,
        @JsonInclude(JsonInclude.Include.NON_NULL) FormattedMessage formattedMessage) {
}

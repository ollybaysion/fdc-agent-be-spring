package fdc.agent.chat;

import java.util.List;
import java.util.Map;

/**
 * 한 조달 수단의 바인드 해석 결과 — 프로즈가 아니라 <b>타입</b>이다(#38 T13).
 *
 * <p>전에는 실패 사유가 예외 메시지 문자열뿐이라, 판정 쪽에서 "pick 이 필요한
 * 상황"을 알아내려면 substring 매칭이 됐다. 여기서 갈래를 타입으로 승격해
 * {@link RetrieveDataTool}(채팅 경로)과 {@link PanelJudge}(패널 판정 경로)가
 * <b>같은 해석기</b>({@link BindResolver})의 결과를 각자 소비한다 — 채팅은
 * 모델에 되먹일 프로즈로, 판정은 카드/pick/보류로.
 *
 * <p>앞 조달을 가리키는 자리가 스텝 인덱스에서 <b>조달 id</b> 로 바뀌었다(spec v3) —
 * 카탈로그에는 "몇 번째"가 없다.
 */
public sealed interface BindOutcome {

    /** 모든 바인드가 풀렸다 — 이 값들로 SQL 을 렌더하면 된다. */
    record Ready(Map<String, String> binds) implements BindOutcome {
    }

    /** 앞 조달 결과가 여러 값 — {@code column} 의 후보 중 하나를 골라야 한다. */
    record NeedPick(String query, String column, List<String> candidates) implements BindOutcome {
    }

    /** 앞 조달({@code query}) 결과가 아직 도착하지 않았다 — 그것이 먼저다. */
    record MissingUpstream(String query, String queryId) implements BindOutcome {
    }

    /** 앞 조달이 0행으로 확인됐다 — 이어갈 수 없고, 없다는 사실이 답이다. */
    record EmptyUpstream(String query) implements BindOutcome {
    }

    /** 그 외 진행 불가 — 사람이 읽을 사유(인자 누락·배선 결함·컬럼 부재 등). */
    record Blocked(String reason) implements BindOutcome {
    }
}

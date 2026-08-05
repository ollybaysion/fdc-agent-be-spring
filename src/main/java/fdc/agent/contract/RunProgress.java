package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 절차 하나의 판정 결과 보고 — panel-judge 응답의 run 별 행이다(#38).
 * BE 가 절차를 어떻게 인지했는지의 선언이고, {@code holds} 는 판정 불가 사유
 * (등재되지 않은 스킬 등)를 사람이 볼 수 있게 한다 — 조용한 멈춤 금지.
 *
 * <p>진행의 단위가 spec v3 에서 <b>단계에서 need 로</b> 바뀌었다. "3단계 중 1단계
 * 도착"은 조회를 세는 말이라 질문에 답했는지는 세어지지 않는다. 여기 실리는
 * {@code needs} 는 알아야 할 것 하나하나의 상태이고, {@code outcome} 이 그 총합이다.
 *
 * <p>{@code terminal} 은 {@code outcome != PROCURABLE} — <b>충분이든 답불가든</b>
 * 더 조달할 것이 없으면 절차는 끝이다. 못 채운 것이 남은 채로 끝나는 것도 정상적인
 * 결말이고, 그 사실이 답이다.
 */
public record RunProgress(
        String skill,
        Map<String, String> args,
        String label,
        int needCount,
        int metCount,
        List<String> wanted,
        boolean terminal,
        String outcome,
        List<Need> needs,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<StepHold> holds) {

    /**
     * 알아야 할 것 하나의 상태. {@code state} 는 {@code INACTIVE · PENDING_GATE ·
     * UNFILLED · FILLED · UNPROCURABLE}.
     *
     * @param source 지목한 조달이 아닌 <b>다른 경로</b>로 채워졌으면 그 스냅샷의 키.
     *     시킨 조회로 채워졌으면 null — 채움 폭포의 1차와 2차 이상을 가르는 자리다.
     */
    public record Need(String id, String what, String state,
            @JsonInclude(JsonInclude.Include.NON_NULL) String source) {
    }

    /** 판정하지 못한 절차와 그 사유 — 무음 대신 보고. */
    public record StepHold(String queryId, String reason) {
    }
}

package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 절차 하나의 판정 결과 보고 — panel-judge 응답의 run 별 행이다(#38).
 * FE 는 이 목록으로 진행 표시를 그리고, {@code needsPick}/{@code holds} 로
 * "왜 카드가 안 나왔나"를 사람이 볼 수 있게 한다 — 조용한 멈춤 금지.
 *
 * <p>{@code nextStep} 은 아직 도착하지 않은 첫 단계(-1 = 전부 도착).
 * {@code terminal} 은 {@code nextStep < 0 || emptyAtStep != null} (T5) —
 * 0행 조기 종료도 종결이다.
 */
public record RunProgress(
        String skill,
        Map<String, String> args,
        String label,
        int stepCount,
        int arrivedCount,
        int nextStep,
        boolean terminal,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer emptyAtStep,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PickNeed> needsPick,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<StepHold> holds) {

    /**
     * 앞 단계 결과가 여러 값이라 하나를 골라야 진행되는 자리(T8) — FE 는 후보를
     * pick 카드로 보여 주고, 선택값을 요청 body 의 {@code picks[queryId]} 로 되보낸다.
     * 후보 밖의 값은 판정이 받지 않는다 — 고르되 지어내지는 못한다.
     */
    public record PickNeed(String queryId, String column, List<String> candidates) {
    }

    /** 카드로 나가지 못한 미도착 단계와 그 사유(T1·T2 등) — 무음 대신 보고. */
    public record StepHold(String queryId, String reason) {
    }
}

package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 절차 하나의 판정 결과 보고 — panel-judge 응답의 run 별 행이다(#38).
 * BE 가 절차를 어떻게 인지했는지의 선언이고, {@code holds} 는 판정 불가 사유
 * (등재되지 않은 스킬 등)를 사람이 볼 수 있게 한다 — 조용한 멈춤 금지.
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
        @JsonInclude(JsonInclude.Include.NON_NULL) List<StepHold> holds) {

    /** 판정하지 못한 절차와 그 사유 — 무음 대신 보고. */
    public record StepHold(String queryId, String reason) {
    }
}

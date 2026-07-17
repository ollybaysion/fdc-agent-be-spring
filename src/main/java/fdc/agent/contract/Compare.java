package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 1:1 비교 계약(@fdc/contract equipment.ts CompareResponse 대응).
 * 숫자 값은 Number 로 두어 JS 의 수 표기(정수는 소수점 없이)를 유지한다.
 */
public final class Compare {
    private Compare() {
    }

    public static final List<String> RECIPES = List.of("RECIPE_X", "RECIPE_Y", "RECIPE_Z");
    public static final List<Integer> WINDOWS = List.of(1, 7, 30);

    public record SensorStats(String sensor, Number mean, Number stddev, Number max, Number min, Number anomalies) {
    }

    public record MatchedRun(String id, String startTime, Number durationMin) {
    }

    /** matchedRun 은 계약상 nullable — null 이어도 키를 유지해 직렬화한다. */
    public record CompareSide(
            String equipmentId,
            String setupTime,
            @JsonInclude(JsonInclude.Include.ALWAYS) MatchedRun matchedRun,
            List<SensorStats> sensorStats) {
    }

    /** 센서별 시계열. 각 point = { t, [equipmentId]: value } — 전부 number. */
    public record SensorSeries(String sensor, List<Map<String, Number>> data) {
    }

    public record ChamberEvent(
            Number start,
            @JsonInclude(JsonInclude.Include.NON_NULL) Number end,
            String type,
            String label) {
    }

    public record RootCause(String sensor, String chamber, String condition, Number value) {
    }

    public record AlarmEvent(
            Number time,
            @JsonInclude(JsonInclude.Include.NON_NULL) Number end,
            String code,
            String label,
            String severity,
            @JsonInclude(JsonInclude.Include.NON_NULL) RootCause rootCause) {
    }

    /** current/baseline 짝(chamberEvents·alarms 공용). */
    public record Sides<T>(T current, T baseline) {
    }

    public record CompareResponse(
            String recipe,
            Number windowDays,
            CompareSide current,
            CompareSide baseline,
            List<SensorSeries> series,
            Sides<List<ChamberEvent>> chamberEvents,
            Sides<List<AlarmEvent>> alarms) {
    }
}

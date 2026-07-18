package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * `done` 이벤트에 실리는 표. FE MessageTable 호환 —
 * { rows: Record<string, unknown>[], columns?, title? }. columns 가 비면
 * FE 가 첫 row 의 키에서 자동 추출.
 */
public record ChatTable(
        @JsonInclude(JsonInclude.Include.NON_NULL) String title,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> columns,
        List<Map<String, Object>> rows) {
}

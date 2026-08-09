package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 캡처 분류 카탈로그의 항목 하나 — akg {@code screen-map} 문서 하나 = 화면
 * 하나(#63). {@code name}·{@code hints} 는 LLM 분류 프롬프트에 실리는 재료이고,
 * {@code program}·{@code menuPath} 는 ④ 직접 선택 브라우저의 group-by 재료다.
 *
 * <p>{@code id}·{@code name} 만 필수다(카탈로그 조회·화면 표시의 최소 바닥) —
 * 나머지는 저작자가 안 채우면 null 로 느슨하게 수용한다. {@code expectedColumns}
 * 는 표 추출 단계(#63 후속)가 쓸 재료라 이번 구현은 파싱만 하고 소비하지 않는다.
 */
public record ScreenMap(
        String id,
        String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) String program,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> menuPath,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> hints,
        @JsonInclude(JsonInclude.Include.NON_NULL) ExpectedColumns expectedColumns) {

    public ScreenMap {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("screen-map id 누락");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("screen-map name 누락");
        }
    }

    /** 이 화면에서 추출될 표가 갖춰야 할 컬럼 — 필수/선택. 이번 구현은 미소비. */
    public record ExpectedColumns(
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> required,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> optional) {
    }
}

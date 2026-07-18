package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 셋업/설비변경/정비 이벤트. label 은 옵션(없으면 JSON 에서 생략 — zod optional 대응). */
public record SetupEvent(
        String time,
        String type,
        @JsonInclude(JsonInclude.Include.NON_NULL) String label) {
}

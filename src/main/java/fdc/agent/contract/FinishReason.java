package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 한 턴 종료 사유 — SSE done 페이로드의 finishReason.
 * BE 내부에서만 생성되고, FE 로는 소문자 문자열로 직렬화된다("stop" | "length").
 */
public enum FinishReason {
    STOP("stop"),
    LENGTH("length");

    private final String wire;

    FinishReason(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}

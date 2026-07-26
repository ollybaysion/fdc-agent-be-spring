package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 대화 메시지 역할. FE·OpenAI 프로토콜과 소문자 문자열로 주고받는다
 * (user | assistant | system | tool).
 */
public enum Role {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    /** 직렬화(FE·OpenAI): 소문자 와이어 문자열. */
    @JsonValue
    public String wire() {
        return wire;
    }

    /** 역직렬화(FE 요청): 소문자 문자열 → enum(대소문자 무시). */
    @JsonCreator
    public static Role from(String v) {
        if (v != null) {
            for (Role r : values()) {
                if (r.wire.equalsIgnoreCase(v)) {
                    return r;
                }
            }
        }
        throw new IllegalArgumentException("unknown role: " + v);
    }
}

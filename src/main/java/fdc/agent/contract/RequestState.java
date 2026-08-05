package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 조달 원장 한 줄의 상태 — 그 조달을 <b>지금 사람이 실행할 수 있는가</b>, 못 한다면
 * 왜인가. 원장은 이벤트가 아니라 상태 전량이라(FE 는 replace) 화면은 이 값만 보고
 * 그리면 되고, 무엇을 감출지 판단하지 않는다.
 *
 * <p>{@link #BLOCKED} 와 {@link #UNREACHABLE} 을 가르는 것이 이 enum 의 핵심이다.
 * 전자는 <b>아직</b> 못 여는 것(앞 조달이 도착하면 열린다)이고 후자는 <b>영영</b>
 * 못 여는 것(앞 조달이 0행으로 확정돼 이어갈 값이 없다)이다. 둘을 뭉치면 화면이
 * 기다리라고 말해야 할 자리에서 포기하라고 말하거나 그 반대가 된다.
 */
public enum RequestState {

    /** 바인드가 다 풀렸다 — SQL 이 완성돼 있고 사람이 복사해 실행하면 된다. */
    READY("ready"),

    /** 앞 조달 결과가 있어야 SQL 이 완성된다 — 지금은 무엇이 필요한지만 말할 수 있다. */
    BLOCKED("blocked"),

    /** 이미 결과가 도착했다(행이 있든 0행으로 확인됐든). */
    ARRIVED("arrived"),

    /**
     * 지금 이 조회를 부를 이유가 없다 — 이 조달을 지목한 need 가 갈래 밖이거나
     * ({@code when} 미충족), 다른 경로로 이미 채워졌거나, 아무 need 도 안 부른다.
     * 사람에게 이미 아는 것을 다시 조회시키지 않는다.
     */
    INACTIVE("inactive"),

    /** 앞 조달이 빈손으로 확정돼 영영 돌 수 없다 — 기다려도 열리지 않는다. */
    UNREACHABLE("unreachable");

    private final String wire;

    RequestState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}

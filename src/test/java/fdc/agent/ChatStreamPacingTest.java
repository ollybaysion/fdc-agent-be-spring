package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.config.AppProps;
import org.junit.jupiter.api.Test;

/** 타이핑 연출이 응답을 붙드는 총 시간에 상한이 있다. */
class ChatStreamPacingTest {

    private static final AppProps.Chat DEFAULTS = new AppProps.Chat(15, 3000);

    @Test
    void 짧은_답변은_설정한_간격_그대로() {
        // 100자 × 15ms = 1.5초 — 상한(3초) 안이라 손대지 않는다.
        assertThat(DEFAULTS.intervalFor(100)).isEqualTo(15);
    }

    @Test
    void 긴_답변은_간격을_좁혀_상한_안에_들어온다() {
        // 1000자 × 15ms 면 15초를 연출에만 쓴다 — 상한에 맞춰 3ms 로.
        assertThat(DEFAULTS.intervalFor(1000)).isEqualTo(3);
        assertThat(DEFAULTS.intervalFor(1000) * 1000).isLessThanOrEqualTo(3000);
    }

    @Test
    void 간격을_0으로_두면_지연이_없다() {
        assertThat(new AppProps.Chat(0, 3000).intervalFor(500)).isZero();
    }

    @Test
    void 상한을_0으로_두면_간격만_따른다() {
        assertThat(new AppProps.Chat(15, 0).intervalFor(1000)).isEqualTo(15);
    }
}

package fdc.agent.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FE→BE→LLM 왕복을 눈으로 좇기 위한 트레이스 로그. 전용 로거 {@code fdc.trace}
 * 하나로 묶여 있어 켜고 끄는 것이 레벨 한 줄이다 — application.yml 의
 * {@code logging.level.fdc.trace}(기본 info, {@code TRACE_LOG_LEVEL=off} 로 끔).
 * 요청 식별자는 {@link fdc.agent.config.RequestIdFilter} 가 MDC 에 넣어 두므로
 * 로그 패턴이 붙여 주고, 그것으로 한 왕복의 줄들이 묶인다.
 *
 * <p>규율 둘: ① 트레이스는 절대 요청을 죽이지 않는다 — 직렬화가 실패해도
 * 사유만 남기고 넘어간다. ② 큰 페이로드는 잘라 찍는다 — 붙여넣은 표는 수천 행이라
 * 그대로 흘리면 정작 봐야 할 줄이 묻힌다.
 */
public final class Trace {

    private static final Logger log = LoggerFactory.getLogger("fdc.trace");
    private static final ObjectWriter WRITER = new ObjectMapper().writerWithDefaultPrettyPrinter();

    /** 한 줄에 찍을 최대 문자 수 — 넘으면 잘라내고 얼마나 잘렸는지 남긴다. */
    private static final int MAX_CHARS = 20_000;

    private Trace() {
    }

    /** 켜져 있는지 — 페이로드를 만드는 비용 자체를 아끼고 싶을 때 먼저 묻는다. */
    public static boolean on() {
        return log.isInfoEnabled();
    }

    /** 라벨 + 페이로드(JSON pretty). */
    public static void emit(String label, Object payload) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("── {} ──\n{}", label, cap(json(payload)));
    }

    /** 라벨 + 이미 문자열인 페이로드(HTTP 원문 등) — 다시 감싸지 않고 그대로. */
    public static void raw(String label, String text) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("── {} ──\n{}", label, cap(text));
    }

    private static String json(Object payload) {
        try {
            return WRITER.writeValueAsString(payload);
        } catch (Exception e) {
            return "(직렬화 실패: " + e + ") " + payload;
        }
    }

    private static String cap(String s) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= MAX_CHARS) {
            return s;
        }
        return s.substring(0, MAX_CHARS)
                + "\n… (" + (s.length() - MAX_CHARS) + "자 생략 — 총 " + s.length() + "자)";
    }
}

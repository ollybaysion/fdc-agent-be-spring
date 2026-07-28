package fdc.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 검증된 런타임 설정. 환경 변수 이름 계약(NODE_ENV, DATA_SOURCE,
 * ORACLE_*, LLM_*, AKG_*)은 application.yml 의 placeholder 가 유지한다.
 */
@ConfigurationProperties(prefix = "fdc")
public record AppProps(
        @DefaultValue("development") Env env,
        @DefaultValue("fixture") String dataSource,
        @DefaultValue Oracle oracle,
        @DefaultValue Llm llm,
        @DefaultValue Akg akg,
        @DefaultValue Chat chat) {

    /**
     * 실행 환경 — 외부 서비스(LLM·AKG) 접근 가능 여부를 가른다.
     * DEVELOPMENT: 로컬 개발, 외부 접근 가능.
     * PRODUCTION : 운영 배포, 외부 접근 가능(추후 운영 서버).
     * RESTRICTED : 제한망, 외부 LLM·AKG 접근 불가 → mock·번들만 사용.
     */
    public enum Env { DEVELOPMENT, PRODUCTION, RESTRICTED }

    public record Oracle(
            String user,
            String password,
            String connectString,
            @DefaultValue("2") int poolMin,
            @DefaultValue("10") int poolMax) {
        public boolean isConfigured() {
            return connectString != null && !connectString.isBlank();
        }
    }

    /**
     * 온프렘 LLM GW. {@code timeoutSeconds} = 한 호출의 응답 대기 상한 — 온프렘 GW 가
     * 응답을 안 주면 요청 스레드가 영영 붙들리지 않도록 하는 안전선이다.
     */
    public record Llm(
            String baseUrl, String apiKey, String model,
            @DefaultValue("60") int timeoutSeconds) {
        public boolean isConfigured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    /**
     * 채팅 스트리밍 연출. 답변은 이미 완성돼 있고 라우트가 한 글자씩 흘려보내
     * 타이핑을 흉내낸다 — {@code tokenIntervalMs} 가 그 간격이다(0 = 지연 없음).
     *
     * <p>{@code maxStreamDelayMs} 는 그 연출이 응답을 붙드는 총 시간의 상한이다.
     * 간격만 있고 상한이 없으면 답변 길이가 곧 대기 시간이 된다(15ms × 500자 = 7.5초).
     * 길면 간격을 좁혀 상한 안에 맞춘다 — 글자 단위 이벤트는 그대로다.
     */
    public record Chat(
            @DefaultValue("15") long tokenIntervalMs,
            @DefaultValue("3000") long maxStreamDelayMs) {

        /** 이 길이의 답변을 상한 안에 흘리려면 글자당 몇 ms 쉬어야 하나. */
        public long intervalFor(int codePointCount) {
            if (tokenIntervalMs <= 0 || codePointCount <= 0) {
                return 0;
            }
            if (maxStreamDelayMs <= 0) {
                return tokenIntervalMs;
            }
            return Math.min(tokenIntervalMs, maxStreamDelayMs / codePointCount);
        }
    }

    /**
     * akg 지식 허브 연동(이슈 #8) — url 이 비어 있으면 연동이 꺼지고
     * classpath 번들 스킬만 쓴다. refreshSeconds = 스킬 목록 재확인 주기
     * (0 = 매 요청 확인, 테스트·디버그용).
     */
    public record Akg(String url, String token, @DefaultValue("300") int refreshSeconds) {
        public boolean isConfigured() {
            return url != null && !url.isBlank();
        }
    }

    /** 운영 환경 — 에러 응답에서 내부 메시지를 감춘다. */
    public boolean isProd() {
        return env == Env.PRODUCTION;
    }

    /** 제한망 — 외부 LLM·AKG 접근 불가(mock·번들 강제). */
    public boolean isRestricted() {
        return env == Env.RESTRICTED;
    }

    public boolean isOracle() {
        return "oracle".equals(dataSource);
    }
}

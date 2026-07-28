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
        @DefaultValue Akg akg) {

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

    public record Llm(String baseUrl, String apiKey, String model) {
        public boolean isConfigured() {
            return baseUrl != null && !baseUrl.isBlank();
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

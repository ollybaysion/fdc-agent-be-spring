package fdc.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 검증된 런타임 설정(Node 판 env.ts 대응). 환경 변수 이름 계약(DATA_SOURCE,
 * ORACLE_*, LLM_*)은 application.yml 의 placeholder 가 유지한다.
 */
@ConfigurationProperties(prefix = "fdc")
public record AppProps(
        @DefaultValue("development") String env,
        @DefaultValue("fixture") String dataSource,
        @DefaultValue Oracle oracle,
        @DefaultValue Llm llm) {

    public record Oracle(
            String user,
            String password,
            String connectString,
            @DefaultValue("2") int poolMin,
            @DefaultValue("10") int poolMax) {
    }

    public record Llm(String baseUrl, String apiKey, String model) {
    }

    public boolean isProd() {
        return "production".equals(env);
    }

    public boolean isOracle() {
        return "oracle".equals(dataSource);
    }
}

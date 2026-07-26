package fdc.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * FE(demo-fe)의 /api/fdc/v1/* 계약을 구현하는 에이전트 백엔드 —
 * BACKEND_URL 스왑만으로 교체 가능해야 한다.
 *
 * DataSourceAutoConfiguration 제외: ojdbc 가 classpath 에 있어도 fixture 모드
 * (DATA_SOURCE 미설정)에서 DataSource 없이 기동해야 한다. oracle 모드의
 * DataSource 는 OracleConfig 가 ORACLE_* 환경변수로 직접 구성한다.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class FdcAgentBeApplication {
    public static void main(String[] args) {
        SpringApplication.run(FdcAgentBeApplication.class, args);
    }
}

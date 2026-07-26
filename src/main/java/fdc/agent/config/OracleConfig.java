package fdc.agent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Oracle 커넥션 풀. DATA_SOURCE=oracle 일
 * 때만 활성 — fixture 모드/테스트는 DataSource 없이 기동한다.
 * ojdbc thin 드라이버 = Instant Client 불필요, 19c 호환.
 */
@Configuration
@ConditionalOnProperty(name = "fdc.data-source", havingValue = "oracle")
public class OracleConfig {

    @Bean(destroyMethod = "close")
    public HikariDataSource oracleDataSource(AppProps props) {
        AppProps.Oracle o = props.oracle();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:oracle:thin:@" + require(o.connectString(), "ORACLE_CONNECT_STRING"));
        cfg.setUsername(require(o.user(), "ORACLE_USER"));
        cfg.setPassword(require(o.password(), "ORACLE_PASSWORD"));
        cfg.setMinimumIdle(o.poolMin());
        cfg.setMaximumPoolSize(o.poolMax());
        cfg.setReadOnly(true); // read-only 계정 전제(API.md §보안)
        return new HikariDataSource(cfg);
    }

    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    private static String require(String v, String name) {
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException(
                    "DATA_SOURCE=oracle 인데 " + name + " 가 비어 있습니다. .env 를 확인하세요.");
        }
        return v;
    }
}

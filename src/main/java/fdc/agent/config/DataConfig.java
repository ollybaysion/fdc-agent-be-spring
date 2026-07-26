package fdc.agent.config;

import fdc.agent.chat.ChatAgent;
import fdc.agent.data.EquipmentRepo;
import fdc.agent.data.fixtures.FixtureRepo;
import fdc.agent.data.oracle.OracleEquipmentRepo;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.MockLlm;
import fdc.agent.llm.OpenAiLlm;
import fdc.agent.skills.AkgSkillSource;
import fdc.agent.skills.SkillQuery;
import fdc.agent.skills.SkillRegistry;
import fdc.agent.skills.SkillSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * seam 배선. env(restricted 면 외부 차단) + 리소스 설정에 따라
 * mock↔openai, 번들↔akg 를 갈아끼운다. fixture↔oracle 는 dataSource 로 별개.
 */
@Configuration
public class DataConfig {

    @Bean
    public EquipmentRepo equipmentRepo(AppProps props, ObjectProvider<JdbcClient> jdbc) {
        return props.isOracle() ? new OracleEquipmentRepo(jdbc.getObject()) : new FixtureRepo();
    }

    @Bean
    public SkillQuery skillQuery(AppProps props, ObjectProvider<JdbcClient> jdbc) {
        if (props.isOracle()) {
            JdbcClient client = jdbc.getObject();
            return (sql, binds) -> client.sql(sql).params(binds).query().listOfRows();
        }
        return SkillRegistry.FIXTURE_SKILL_QUERY;
    }

    @Bean
    public LlmClient llmClient(AppProps props) {
        AppProps.Llm llm = props.llm();
        return !props.isRestricted() && llm.isConfigured()
                ? new OpenAiLlm(llm.baseUrl(), llm.apiKey(), llm.model())
                : new MockLlm();
    }

    /**
     * 스킬 출처 seam — AKG_URL 설정 시 지식 허브에서 런타임 fetch(#8),
     * 미설정 시 classpath 번들(기존 동작 그대로).
     */
    @Bean
    public SkillSource skillSource(AppProps props) {
        AppProps.Akg akg = props.akg();
        return !props.isRestricted() && akg.isConfigured()
                ? new AkgSkillSource(akg.url(), akg.token(), akg.refreshSeconds())
                : SkillRegistry::bundledSpecs;
    }

    @Bean
    public ChatAgent chatAgent(
            LlmClient llm, EquipmentRepo repo, SkillQuery skillQuery, SkillSource skillSource) {
        return new ChatAgent(llm, repo, skillQuery, skillSource);
    }
}

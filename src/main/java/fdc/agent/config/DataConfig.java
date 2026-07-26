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
 * seam 배선. DATA_SOURCE / LLM_BASE_URL 에 따라
 * fixture↔oracle, mock↔openai 구현을 갈아끼운다.
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
        return llm.baseUrl() != null && !llm.baseUrl().isEmpty()
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
        boolean hubOn = akg != null && akg.url() != null && !akg.url().isEmpty();
        return hubOn
                ? new AkgSkillSource(akg.url(), akg.token(), akg.refreshSeconds())
                : SkillRegistry::bundledSpecs;
    }

    @Bean
    public ChatAgent chatAgent(
            LlmClient llm, EquipmentRepo repo, SkillQuery skillQuery, SkillSource skillSource) {
        return new ChatAgent(llm, repo, skillQuery, skillSource);
    }
}

package fdc.agent.config;

import fdc.agent.chat.ChatAgent;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.MockLlm;
import fdc.agent.llm.OpenAiLlm;
import fdc.agent.lines.AkgLineSource;
import fdc.agent.lines.LineSource;
import fdc.agent.skills.AkgSkillSource;
import fdc.agent.skills.SkillQuery;
import fdc.agent.skills.SkillRegistry;
import fdc.agent.skills.SkillSource;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * seam 배선. env(restricted 면 외부 차단) + 리소스 설정에 따라
 * mock↔openai, 번들↔akg 를 갈아끼운다. 스킬 조회의 fixture↔oracle 는
 * dataSource 로 별개.
 */
@Configuration
public class DataConfig {

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
                ? new OpenAiLlm(llm.baseUrl(), llm.apiKey(), llm.model(), llm.timeoutSeconds())
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

    /**
     * 라인 출처 seam — 스킬과 같은 스위치를 탄다. 미설정/제한망이면 <b>빈 목록</b>:
     * BE 는 라인 코드를 지어내지 않고, 빈 목록을 받은 화면은 라인 칸을 안 그린다.
     */
    @Bean
    public LineSource lineSource(AppProps props) {
        AppProps.Akg akg = props.akg();
        return !props.isRestricted() && akg.isConfigured()
                ? new AkgLineSource(akg.url(), akg.token(), akg.refreshSeconds())
                : List::of;
    }

    @Bean
    public ChatAgent chatAgent(LlmClient llm, SkillQuery skillQuery, SkillSource skillSource) {
        return new ChatAgent(llm, skillQuery, skillSource);
    }
}

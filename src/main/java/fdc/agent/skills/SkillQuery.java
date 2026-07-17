package fdc.agent.skills;

import java.util.List;
import java.util.Map;

/** 스텝 SQL 을 실행하는 함수(bind 만 값). oracle=JdbcClient, fixture=seed. */
@FunctionalInterface
public interface SkillQuery {
    List<Map<String, Object>> query(String sql, Map<String, Object> binds);
}

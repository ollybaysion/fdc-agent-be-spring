package fdc.agent.skills;

import java.util.List;
import java.util.Map;

/**
 * 스텝 간 bind 배선(스킬마다 짝 wiring.json). spec.json 에 steps[].binds 필드가
 * 생기면 spec 하나로 통합된다(원본 이슈 #4 §wiring 갭).
 */
public record SkillWiring(List<SkillArg> args, Map<Integer, Map<String, BindSource>> binds) {

    /** 툴 파라미터 하나(이름 + 설명). */
    public record SkillArg(String name, String description) {
    }

    /** 한 bind 값의 출처: from="arg"(툴 인자) 또는 from="step"(이전 스텝 결과 컬럼). */
    public record BindSource(String from, String arg, Integer step, String column) {
    }
}

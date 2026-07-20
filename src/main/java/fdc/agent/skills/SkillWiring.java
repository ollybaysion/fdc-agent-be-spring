package fdc.agent.skills;

import java.util.Map;

/**
 * 스텝 간 bind 배선(스킬마다 짝 wiring.json). spec.json 에 steps[].binds 필드가
 * 생기면 spec 하나로 통합된다(원본 이슈 #4 §wiring 갭).
 *
 * <p>v2 부터 툴 인자 목록은 여기가 아니라 {@code spec.inputs} 가 소유한다 —
 * 인자 계약이 두 파일로 갈라지던 드리프트를 없앴다. wiring 은 배선만 남는다.
 */
public record SkillWiring(Map<Integer, Map<String, BindSource>> binds) {

    /** 한 bind 값의 출처: from="arg"(툴 인자) 또는 from="step"(이전 스텝 결과 컬럼). */
    public record BindSource(String from, String arg, Integer step, String column) {
    }
}

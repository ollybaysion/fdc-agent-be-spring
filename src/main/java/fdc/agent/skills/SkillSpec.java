package fdc.agent.skills;

import java.util.List;

/**
 * forge-domain-skill 의 spec.json (v2). spec 파일 자체가 언어 중립 진실원이고,
 * 형식의 표준은 agent-skill-foundry 의 {@code forge/render-skill.mjs}
 * ({@code validateSpec}) 이다.
 *
 * <p>v2 에서 {@code description} 은 spec 필드가 아니다 — {@code scope + focus +
 * inputs} 에서 <b>합성</b>된다({@link SkillLoader#synthesizeDescription}). 합성
 * 문자열을 공유 픽스처로 묶지 않고 소비자가 각자 만들기로 한 결정이라(foundry
 * 설계 §4-1), 골격이 바뀌면 로더도 같이 고쳐야 한다.
 *
 * <p>{@code valueRules} 는 제거됐다 — 값 의미(코드표)는 표준 db-schema 문서
 * 소관이며 스킬마다 복붙하지 않는다.
 */
public record SkillSpec(
        String name,
        String argumentHint,
        SkillScope scope,
        String focus,
        String anchorTable,
        String intro,
        List<SkillInput> inputs,
        List<SkillDependency> dependencies,
        List<SkillStep> steps,
        SkillOutput output,
        String discipline) {

    /** 스코핑 지문. 의도는 "상태" | "생성 이력" — description 골격을 고른다. */
    public record SkillScope(String 단위, String 카디널리티, String 의도) {
    }

    /** 툴 파라미터 하나. v2 부터 인자 계약의 진실원은 wiring 이 아니라 여기다. */
    public record SkillInput(String name, boolean required, String description) {
    }

    public record SkillDependency(String mcp, List<String> tools, String why) {
    }

    /** 흐름 제어(멈춤·스킵). when 은 프로즈가 아니라 조건식. */
    public record SkillBranch(String when, String then) {
    }

    /**
     * 조회 스텝. {@code produces} = 이 스텝이 답에 기여하는 차원 한 마디로,
     * 로더가 이어서 출력 지침의 <b>반드시 포함</b> 줄을 만든다. 조회만 하고
     * 답에는 안 들어가는 스텝(ID 해소 등)은 비어 있다.
     */
    public record SkillStep(
            String title,
            String produces,
            String lead,
            String sql,
            List<SkillBranch> branches,
            String notes) {
    }

    public record SkillExample(String ask, String answer) {
    }

    /**
     * 출력 안내. 형식은 강제하지 않고 내용에만 바닥을 둔다 — {@code avoid} 는
     * 「끌리는 오추론」 — 「금하는 데이터 사실」 형태의 도메인 금지, {@code examples}
     * 는 넓은 질문 + 좁은 질문의 대비쌍(값·길이 베끼기 방지).
     */
    public record SkillOutput(List<String> avoid, List<SkillExample> examples) {
    }
}

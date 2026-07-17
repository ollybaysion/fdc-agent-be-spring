package fdc.agent.skills;

import java.util.List;

/**
 * forge-domain-skill 의 spec.json (Node 판 skill-loader.ts 타입 대응).
 * spec 파일 자체가 언어 중립 진실원 — Node 판과 같은 JSON 을 그대로 읽는다.
 */
public record SkillSpec(
        String name,
        String argumentHint,
        String description,
        String h1Title,
        String intro,
        List<SkillStep> steps,
        List<SkillValueRule> valueRules,
        SkillOutput output,
        String discipline) {

    public record SkillStep(String title, String lead, String sql, String notes) {
    }

    public record SkillValueRule(String target, String rule, String basis) {
    }

    public record SkillOutput(String lead, String template, String example, String exampleLabel) {
    }
}

package fdc.agent.skills;

import java.util.List;
import java.util.Map;

/**
 * forge-domain-skill 의 spec.json (v2). spec 파일 자체가 언어 중립 진실원이고,
 * 형식의 표준은 agent-knowledge-governance(akg)의
 * {@code schemas/domain-skill/v1.schema.json} + json-spec §4.4 다 — 포맷
 * 진실원이 akg 로 이관 발효됐고 foundry 렌더러는 삭제됐다(asf #13).
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

    /**
     * 흐름 제어 분기. {@code when} 은 <b>문법 없는 산문</b>이다 — 같은 뜻도 spec 마다
     * 표기가 제각각일 수 있어({@code rows = 0}, {@code CNT=0}, "건수가 없으면") 기계
     * 평가하지 않고, 분기 있는 스텝의 데이터가 도착하면 LLM 이 성립 여부를 판정한다
     * (#55, 방법 1). {@code opens} 가 있으면 <b>열림형</b>(성립 시 그  0-기반 스텝이
     * 열린다 — 대상 스텝은 기본 잠김), 없으면 <b>종료형</b>(성립 시 절차 종결,
     * {@code then} 산문이 종결 서술 지시에 실린다).
     */
    public record SkillBranch(String when, String then, Integer opens) {
    }

    /**
     * 조회 스텝. {@code produces} = 이 스텝이 답에 기여하는 차원 한 마디로,
     * 로더가 이어서 출력 지침의 <b>반드시 포함</b> 줄을 만든다. 조회만 하고
     * 답에는 안 들어가는 스텝(ID 해소 등)은 비어 있다.
     *
     * <p>{@code table} = 이 스텝의 원천 테이블명(akg #44 제안 필드) — 서술 맥락의
     * 데이터 블록 헤딩과 db-schema 발췌 키가 쓴다. 아직 스키마에 없는 spec 은
     * null 이고, 소비자는 SQL FROM 파싱({@link SqlRender#tableOf})으로 유도한다.
     */
    public record SkillStep(
            String title,
            String table,
            String produces,
            String lead,
            String sql,
            Map<String, BindSource> binds,
            List<SkillBranch> branches,
            String notes) {
    }

    /**
     * 한 bind 값의 출처(akg json-spec v0.6.0 {@code steps[].binds}) —
     * from="arg"(툴 인자) 또는 from="step"(앞 스텝 결과 컬럼). wiring.json
     * 사이드카를 spec 이 흡수한 자리라 스킬 하나 = 파일 하나다(akg #32).
     */
    public record BindSource(String from, String arg, Integer step, String column) {
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

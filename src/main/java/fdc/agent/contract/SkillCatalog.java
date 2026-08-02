package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * GET /api/fdc/v1/skills 응답 — 사용자가 <b>고를 수 있는</b> 스킬 목록.
 *
 * <p>채팅은 LLM 이 스킬을 고르지만, FE 의 "설비 추가" 진입은 사람이 고른다 —
 * 설비명 + 스킬을 정하면 그 스킬이 요구하는 것들이 곧바로 카드로 선다. 그래서 이
 * 응답은 <b>스킬이 무엇을 요구하는지</b>({@code inputs})와 <b>무엇을 조회하는지</b>
 * ({@code steps})를 그대로 편다.
 *
 * <p>{@code skill} 은 툴 이름(언더스코어)이다 — {@link InputRequest#skill()} 과 같은
 * 값이라, 여기서 만든 입력 카드의 회신이 {@code inputs[skill][key]} 로 곧장 들어맞는다.
 * 사람이 읽는 이름은 합성하지 않는다: spec 의 {@code focus}·{@code unit} 을 그대로
 * 주고 표기는 화면이 정한다(합성 문자열은 소비자가 각자 만든다 — foundry 설계 §4-1).
 */
public record SkillCatalog(List<Entry> skills) {

    /**
     * 스킬 하나. {@code description} 은 spec 필드가 아니라 라우팅 문장 합성분이라
     * (언제 부르나) 화면에서 부연으로만 쓴다.
     */
    public record Entry(
            String skill,
            String name,
            String unit,
            String focus,
            String description,
            @JsonInclude(JsonInclude.Include.NON_NULL) String argumentHint,
            @JsonInclude(JsonInclude.Include.NON_NULL) String anchorTable,
            List<Input> inputs,
            List<Step> steps) {
    }

    /** 스킬 인자 하나 = FE 입력 카드 하나(아직 값이 없다면). */
    public record Input(
            String key,
            boolean required,
            @JsonInclude(JsonInclude.Include.NON_NULL) String description) {
    }

    /**
     * 조회 스텝 하나 = FE 데이터 요청 카드 하나.
     *
     * <p>{@code sql} 은 bind 자리가 {@code :var} 로 남은 <b>미완성</b> SQL 이다.
     * {@code argBinds}(bind 이름 → 스킬 인자 이름)가 있어야 FE 가 사용자가 채운 값으로
     * 그 자리를 메울 수 있고, {@code priorStepBinds} 의 bind 는 <b>앞 스텝 결과</b>에서
     * 오므로 FE 가 채울 수 없다 — 그 카드는 "앞 조회가 먼저" 라고 알려야 한다.
     *
     * <p>{@code binds} 는 배선 전문이다 — bind 이름마다 어느 인자, 또는 어느 스텝의
     * 어느 컬럼이 채우는지. FE 가 판정 왕복 없이 스텝 상태(미정/요청 가능)를 스스로
     * 파생하는 데 쓴다(dataList). {@code argBinds}/{@code priorStepBinds} 는 이것의
     * 요약 뷰로 남는다 — 소비자가 옮겨 가면 걷는다.
     */
    public record Step(
            String title,
            @JsonInclude(JsonInclude.Include.NON_NULL) String produces,
            String sql,
            Map<String, String> argBinds,
            List<String> priorStepBinds,
            Map<String, Bind> binds) {
    }

    /** bind 하나의 배선 — from="arg" 면 {@code arg} 가, from="step" 이면 {@code step}+{@code column} 이 찬다. */
    public record Bind(
            String from,
            @JsonInclude(JsonInclude.Include.NON_NULL) String arg,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer step,
            @JsonInclude(JsonInclude.Include.NON_NULL) String column) {
    }
}

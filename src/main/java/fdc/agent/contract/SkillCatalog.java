package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * GET /api/fdc/v1/skills 응답 — 사용자가 <b>고를 수 있는</b> 스킬 목록.
 *
 * <p>채팅은 LLM 이 스킬을 고르지만, FE 의 "설비 추가" 진입은 사람이 고른다 —
 * 설비명 + 스킬을 정하면 그 스킬이 요구하는 것들이 곧바로 카드로 선다. 그래서 이
 * 응답은 <b>스킬이 무엇을 요구하는지</b>({@code inputs}), <b>무엇을 알아야 하는지</b>
 * ({@code needs}), <b>그걸 어떻게 얻는지</b>({@code queries})를 그대로 편다.
 *
 * <p>spec v3 에서 {@code needs} 가 실리는 것이 핵심이다. FE 는 이 목록으로 판정
 * 왕복 없이 카드 배치를 로컬 파생하는데(demo-fe dataList), v2 에는 "이 조회가 지금
 * 필요한가"를 FE 가 알 재료가 없어서 스텝 전량을 세워 놓고 바인드가 풀리기만
 * 기다렸다. {@code needs[].when} 이 오면 안 열린 갈래의 카드를 아예 안 세운다.
 *
 * <p>{@code skill} 은 툴 이름(언더스코어)이다 — {@link InputRequest#skill()} 과 같은
 * 값이라, 여기서 만든 입력 카드의 회신이 {@code inputs[skill][key]} 로 곧장 들어맞는다.
 * 사람이 읽는 이름은 합성하지 않는다: spec 의 문장을 그대로 주고 표기는 화면이
 * 정한다(합성 문자열은 소비자가 각자 만든다 — foundry 설계 §4-1).
 */
public record SkillCatalog(List<Entry> skills) {

    /**
     * 스킬 하나. {@code description} 은 spec 필드가 아니라 {@code questions} 에서
     * 합성한 라우팅 문장이라(언제 부르나) 화면에서 부연으로만 쓴다.
     */
    public record Entry(
            String skill,
            String name,
            String description,
            List<String> questions,
            String rephrasing,
            @JsonInclude(JsonInclude.Include.NON_NULL) String argumentHint,
            @JsonInclude(JsonInclude.Include.NON_NULL) String anchorTable,
            List<Input> inputs,
            List<Need> needs,
            List<Query> queries) {
    }

    /** 스킬 인자 하나 = FE 입력 카드 하나(아직 값이 없다면). */
    public record Input(
            String key,
            boolean required,
            @JsonInclude(JsonInclude.Include.NON_NULL) String description) {
    }

    /**
     * 알아야 할 것 하나. {@code when} 이 있으면 <b>조건부</b>다 — 그 조건이 맞을
     * 때만 활성이고, 활성이 아닌 need 는 완료에 필요하지도 자기 조달을 부르지도
     * 않는다. {@code filledBy} 가 비어 있으면 이 스킬로는 못 얻는다는 선언이다.
     */
    public record Need(
            String id,
            String what,
            @JsonInclude(JsonInclude.Include.NON_NULL) String when,
            List<Fill> filledBy) {
    }

    /** 채움 자리 하나 — 어느 조달의 어느 컬럼인가. 원소가 여럿이면 OR. */
    public record Fill(String query, String column) {
    }

    /**
     * 조달 수단 하나 = FE 데이터 슬롯 하나.
     *
     * <p>{@code sql} 은 bind 자리가 {@code :var} 로 남은 <b>미완성</b> SQL 이다.
     * {@code argBinds}(bind 이름 → 스킬 인자 이름)가 있어야 FE 가 사용자가 채운 값으로
     * 그 자리를 메울 수 있고, {@code priorQueryBinds} 의 bind 는 <b>다른 조달 결과</b>
     * 에서 오므로 FE 가 채울 수 없다 — 그 카드는 "앞 조회가 먼저" 라고 알려야 한다.
     *
     * <p>{@code binds} 는 배선 전문이다 — bind 이름마다 어느 인자, 또는 어느 조달의
     * 어느 컬럼이 채우는지. FE 가 판정 왕복 없이 슬롯 상태(미정/요청 가능)를 스스로
     * 파생하는 데 쓴다(dataList).
     *
     * <p>{@code label} 은 spec 에 없는 합성분 — 이 조달이 채우는 need 의 말이다.
     * 카드가 조회의 이름이 아니라 <b>그 조회가 답에 기여하는 것</b>을 말하게 된다.
     */
    public record Query(
            String id,
            String label,
            @JsonInclude(JsonInclude.Include.NON_NULL) String table,
            String sql,
            Map<String, String> argBinds,
            List<String> priorQueryBinds,
            Map<String, Bind> binds) {
    }

    /**
     * bind 하나의 배선 — from="arg" 면 {@code arg} 가, from="query" 면 {@code query}
     * +{@code column} 이 찬다.
     */
    public record Bind(
            String from,
            @JsonInclude(JsonInclude.Include.NON_NULL) String arg,
            @JsonInclude(JsonInclude.Include.NON_NULL) String query,
            @JsonInclude(JsonInclude.Include.NON_NULL) String column) {
    }
}

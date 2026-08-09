package fdc.agent.skills;

import java.util.List;
import java.util.Map;

/**
 * domain-skill spec (v3) — 네 칸 {@code questions → rephrasing → needs → queries}.
 * spec 파일 자체가 언어 중립 진실원이고, 형식의 표준은 agent-knowledge-governance(akg)의
 * {@code schemas/domain-skill/v1.schema.json} 다(akg #46, 포맷 진실원은 akg).
 *
 * <p>각 칸은 원래 LLM 이 해야 할 추론 하나이고, 스킬은 그 답을 사람이 미리 채워 둔
 * 정답지다: 무엇을 묻는가({@code questions}) → 답할 수 있는 형태로 다시 말하면
 * 무엇인가({@code rephrasing}) → 그러려면 무엇을 알아야 하는가({@code needs}) →
 * 그걸 어떻게 얻는가({@code queries}).
 *
 * <p><b>{@code needs} 가 1급이고 쿼리는 그걸 채우는 수단이다.</b> v2 는 반대였다 —
 * {@code steps[]} 가 순서대로 늘어서 있고 {@code produces} 가 "이 조회가 무엇을
 * 산출하나"를 적었다. 그 방향에서는 조회가 답을 정하므로, 도착한 행을 놓고
 * "이걸로 답이 되는가"를 판정할 근거가 spec 에 없었다. v3 는 그 판정을 결정론으로
 * 만든다({@link fdc.agent.chat.NeedsResolver}).
 *
 * <p>{@code description} 은 v3 에서도 spec 필드가 아니다 — {@code questions} 에서
 * <b>합성</b>된다({@link SkillLoader#synthesizeDescription}). 합성 문자열을 공유
 * 픽스처로 묶지 않고 소비자가 각자 만들기로 한 결정이라(foundry 설계 §4-1),
 * 골격이 바뀌면 로더도 같이 고쳐야 한다.
 */
public record SkillSpec(
        String name,
        String argumentHint,
        List<String> questions,
        String rephrasing,
        String anchorTable,
        List<SkillInput> inputs,
        List<SkillDependency> dependencies,
        List<SkillNeed> needs,
        List<SpecQuery> queries,
        SkillOutput output,
        String discipline) {

    /**
     * 툴 파라미터 하나. 인자 계약의 진실원은 wiring 이 아니라 여기다.
     *
     * <p>{@code type} 은 입력 위젯 신호(akg json-spec v0.9.0, {@code datetime | date},
     * 없으면 자유 텍스트) — 값은 여전히 문자열이고 bind·실행 계약과 무관하다. BE 는
     * 해석하지 않고 /skills 응답과 입력 요청 카드로 그대로 전달한다.
     */
    public record SkillInput(String name, boolean required, String description, String type) {

        public SkillInput(String name, boolean required, String description) {
            this(name, required, description, null);
        }
    }

    public record SkillDependency(String mcp, List<String> tools, String why) {
    }

    /**
     * 알아야 할 것 하나 — 세 번째 칸. {@code what} 은 묻는 사람의 말로 적고,
     * {@code filledBy} 는 그걸 어디서 채우는지다.
     *
     * <p>{@code when} 은 다른 need 의 값에 걸린 조건({@code sensor_kind = PHYSICAL})
     * 이다. 조건이 안 맞는 need 는 <b>완료에 필요하지도 않고 자기 쿼리를 돌릴 이유도
     * 되지 않는다</b> — v2 의 갈림형 {@code branches} 가 여기로 접혔다.
     *
     * <p>{@code filledBy} 가 <b>비어 있는 것은 유효하며 뜻이 있다</b>: 이 스킬은
     * 그걸 얻을 방법이 없다는 선언이고, 답불가가 런타임에 발견되는 대신 spec 에
     * 적혀 있게 된다.
     */
    public record SkillNeed(String id, String what, String when, List<Fill> filledBy) {

        public List<Fill> fills() {
            return filledBy != null ? filledBy : List.of();
        }
    }

    /**
     * 채움 자리 하나 — 어느 조달 수단의 어느 컬럼인가. 원소가 여럿이면 <b>OR</b>
     * (대안 경로 — 하나만 차면 충족)이고, AND 가 필요하면 need 를 쪼갠다.
     *
     * <p>컬럼까지 못 박는 것은 결정이다: {@code rows > 0} 만 보면 한 쿼리를 여러
     * needs 가 나눠 쓸 때 NULL 컬럼을 못 잡는다.
     */
    public record Fill(String query, String column) {
    }

    /**
     * 조달 수단 하나 — 네 번째 칸. <b>카탈로그이지 흐름이 아니다</b>: 순서에 뜻이
     * 없고(v2 의 {@code steps} 였다), 둘 사이의 유일한 의존은 {@code binds} 의
     * id 참조뿐이다. 어느 것이 도는지는 저작이 아니라 <b>유도</b>된다 — 자기를
     * {@code filledBy} 로 지목한 활성 need 가 하나라도 있을 때 돈다.
     *
     * <p>{@code kind} 는 지금 {@code "sql"} 하나뿐이다. 필드가 있는 이유는 이 칸이
     * "쿼리"가 아니라 조달 수단이기 때문 — Initializer 인덱스 조회나 상수 카탈로그가
     * 나중에 같은 자리에 들어온다.
     */
    public record SpecQuery(
            String id,
            String kind,
            String table,
            String sql,
            Map<String, BindSource> binds,
            String notes) {

        public Map<String, BindSource> bindings() {
            return binds != null ? binds : Map.of();
        }
    }

    /**
     * 한 bind 값의 출처 — {@code from="arg"}(툴 인자) 또는 {@code from="query"}
     * (다른 조달 결과의 컬럼). v2 의 {@code from="step"} + 인덱스가 id 참조로
     * 바뀐 자리다: 순서에 뜻이 없어졌으니 위치로는 가리킬 수 없다.
     */
    public record BindSource(String from, String arg, String query, String column) {
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

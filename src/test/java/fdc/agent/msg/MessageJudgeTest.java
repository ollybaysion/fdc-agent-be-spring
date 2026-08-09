package fdc.agent.msg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 메시지 스니프·LLM 응답 파서(#64 MVP) — 순수 함수 단위. */
class MessageJudgeTest {

    private static final String DUMP =
            "LotProcessResult{eqpId=CVD-01, lotId=LOT-24135, "
                    + "recipe=RecipeInfo{recipeId=R-88, version=3}, "
                    + "steps=[StepResult{stepNo=1, status=OK}]}";

    @Test
    void 스니프는_덤프_모양만_후보로_삼는다() {
        assertThat(MessageJudge.sniff(DUMP)).isTrue();
        assertThat(MessageJudge.sniff("com.acme.Msg{a=1}")).isTrue();
        // 표·일반 텍스트는 즉시 탈락 — 표 경로가 LLM 지연을 물지 않는 근거.
        assertThat(MessageJudge.sniff("COL_A\tCOL_B\n1\t2")).isFalse();
        assertThat(MessageJudge.sniff("그냥 문장")).isFalse();
        assertThat(MessageJudge.sniff("")).isFalse();
        assertThat(MessageJudge.sniff(null)).isFalse();
    }

    @Test
    void 파서는_코드펜스를_벗기고_json_이_객체가_아니면_거른다() {
        assertThat(MessageJudge.parse("```json\n{\"json\":{\"a\":\"1\"}}\n```")).isNotNull();
        // json 이 문자열이면 실패 — FE JSON 뷰가 따옴표 덩어리를 그리게 된다.
        assertThat(MessageJudge.parse("{\"json\":\"문자열\"}")).isNull();
        assertThat(MessageJudge.parse("JSON 아님")).isNull();
    }

    @Test
    void 파서는_제목과_시각을_그대로_싣는다() {
        var out = MessageJudge.parse("""
                {"json":{"a":"1"},"title":"LOT-24135 · R-88",
                 "occurredAt":"2026-08-08T11:58:03.412765"}
                """);
        assertThat(out).isNotNull();
        assertThat(out.title()).isEqualTo("LOT-24135 · R-88");
        // 소수초는 자르지도 채우지도 않는다 — 같은 초 두 건의 순서가 여기 달렸다.
        assertThat(out.occurredAt()).isEqualTo("2026-08-08T11:58:03.412765");
    }

    @Test
    void 시각_문지기는_모양만_본다() {
        assertThat(MessageJudge.asOccurredAt("2026-08-08T11:58:03.412765"))
                .isEqualTo("2026-08-08T11:58:03.412765");
        assertThat(MessageJudge.asOccurredAt("2026-08-08 11:58:03.412"))
                .isEqualTo("2026-08-08 11:58:03.412");
        assertThat(MessageJudge.asOccurredAt("2026-08-08T11:58:03+09:00"))
                .isEqualTo("2026-08-08T11:58:03+09:00");
        assertThat(MessageJudge.asOccurredAt("  2026-08-08T11:58  ")).isEqualTo("2026-08-08T11:58");
        // 모델이 문장·조각을 실으면 버린다 — 정렬 키로 쓸 수 없다.
        assertThat(MessageJudge.asOccurredAt("오전 11시쯤")).isNull();
        assertThat(MessageJudge.asOccurredAt("11:58:03.412765")).isNull();
        assertThat(MessageJudge.asOccurredAt("2026-08-08")).isNull();
        assertThat(MessageJudge.asOccurredAt("")).isNull();
        assertThat(MessageJudge.asOccurredAt(42)).isNull();
        assertThat(MessageJudge.asOccurredAt(null)).isNull();
    }
}

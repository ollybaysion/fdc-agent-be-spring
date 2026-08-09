package fdc.agent.msg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 붙여넣은 덩어리 → 메시지 낱개(#64 다건) — 결정론 분할. */
class MessageSplitterTest {

    @Test
    void 덤프가_연달아_붙어_있으면_최상위_중괄호에서_자른다() {
        var out = MessageSplitter.split(
                "A{eqpId=CVD-01, steps=[S{n=1}]}\nB{eqpId=ETCH-02, r=RecipeInfo{v=3}}");
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).startsWith("A{").endsWith("]}");
        assertThat(out.get(1)).startsWith("B{").endsWith("}}");
    }

    @Test
    void 로그는_타임스탬프로_시작하는_줄마다_자르고_이어지는_줄은_붙인다() {
        var out = MessageSplitter.split("""
                2026-08-08 11:58:03.412 INFO AlarmEvent{alarmId=AL-201}
                2026-08-08 11:58:04.001 INFO LotResult{lotId=LOT-1,
                  note=줄바꿈 필드}
                2026-08-08 11:59:00.000 INFO LotResult{lotId=LOT-2}
                """);
        assertThat(out).hasSize(3);
        // 머리 없는 줄은 앞 건의 이어짐 — 여기서 자르면 한 메시지가 두 동강 난다.
        assertThat(out.get(1)).contains("LOT-1").contains("줄바꿈 필드");
    }

    @Test
    void 한_건이거나_모양을_모르면_자르지_않는다() {
        assertThat(MessageSplitter.split("LotProcessResult{eqpId=CVD-01}")).hasSize(1);
        assertThat(MessageSplitter.split("COL_A\tCOL_B\n1\t2")).hasSize(1);
        assertThat(MessageSplitter.split("  ")).isEmpty();
    }

    @Test
    void 낱개가_전부_덤프를_품어야_메시지_묶음이다() {
        assertThat(MessageSplitter.allLookLikeMessages(
                MessageSplitter.split("A{a=1}\nB{b=2}"))).isTrue();
        // 표는 중괄호가 없다 — 다건으로 오인하면 표 경로가 LLM 지연을 문다.
        assertThat(MessageSplitter.allLookLikeMessages(
                MessageSplitter.split("COL_A\tCOL_B\n1\t2"))).isFalse();
    }
}

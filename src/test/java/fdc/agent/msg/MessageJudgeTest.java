package fdc.agent.msg;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.contract.FormattedMessage;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.llm.LlmTypes.LlmToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 메시지 스니프·배치 파서·배치 호출(#64) — 순수 함수 + 가짜 LLM 단위. */
class MessageJudgeTest {

    private static final String DUMP =
            "LotProcessResult{eqpId=CVD-01, lotId=LOT-24135, "
                    + "recipe=RecipeInfo{recipeId=R-88, version=3}, "
                    + "steps=[StepResult{stepNo=1, status=OK}]}";

    /** 프롬프트의 `<<<MSG n>>>` 번호만 읽어 결과를 짓는 가짜 모델 — 호출 수를 센다. */
    private static final class FakeLlm implements LlmClient {
        final List<List<Integer>> calls = new ArrayList<>();
        /** 이 번호들은 언제 물어도 빼먹는다 — 끝내 변환 못 하는 조각. */
        java.util.Set<Integer> drop = java.util.Set.of();
        /** 처음 물었을 때만 빼먹는다 — 묶음이 한 번 깨졌다가 다시 물으면 오는 상황. */
        java.util.Set<Integer> flaky = new java.util.HashSet<>();

        @Override
        public LlmTurn next(List<LlmMessage> messages, List<LlmToolSpec> tools) {
            Matcher mark = Pattern.compile("<<<MSG (\\d+)>>>").matcher(messages.get(0).content());
            List<Integer> asked = new ArrayList<>();
            while (mark.find()) {
                asked.add(Integer.valueOf(mark.group(1)));
            }
            calls.add(asked);
            String body = asked.stream()
                    .filter(i -> !drop.contains(i) && !flaky.remove(i))
                    .map(i -> "{\"index\":" + i + ",\"json\":{\"n\":\"" + i + "\"}}")
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            return new LlmTurn.Final("[" + body + "]");
        }
    }

    private static String dumps(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append("Msg").append(i).append("{eqpId=CVD-01, n=").append(i).append("}\n");
        }
        return out.toString();
    }

    @Test
    void 스니프는_덤프_모양과_덤프_묶음만_후보로_삼는다() {
        assertThat(MessageJudge.sniff(DUMP)).isTrue();
        assertThat(MessageJudge.sniff("com.acme.Msg{a=1}")).isTrue();
        assertThat(MessageJudge.sniff(dumps(3))).isTrue();
        // 표·일반 텍스트는 즉시 탈락 — 표 경로가 LLM 지연을 물지 않는 근거.
        assertThat(MessageJudge.sniff("COL_A\tCOL_B\n1\t2")).isFalse();
        assertThat(MessageJudge.sniff("그냥 문장")).isFalse();
        assertThat(MessageJudge.sniff("")).isFalse();
        assertThat(MessageJudge.sniff(null)).isFalse();
    }

    @Test
    void 파서는_코드펜스를_벗기고_json_이_객체가_아니면_거른다() {
        assertThat(MessageJudge.parseBatch("```json\n[{\"index\":0,\"json\":{\"a\":\"1\"}}]\n```"))
                .containsKey(0);
        // json 이 문자열이면 실패 — FE JSON 뷰가 따옴표 덩어리를 그리게 된다.
        assertThat(MessageJudge.parseBatch("[{\"index\":0,\"json\":\"문자열\"}]")).isEmpty();
        // 번호가 없으면 자리를 모른다 — 쓸 수 없다.
        assertThat(MessageJudge.parseBatch("[{\"json\":{\"a\":\"1\"}}]")).isEmpty();
        assertThat(MessageJudge.parseBatch("JSON 아님")).isEmpty();
    }

    @Test
    void 파서는_제목과_시각을_그대로_싣는다() {
        var out = MessageJudge.parseBatch("""
                [{"index":0,"json":{"a":"1"},"title":"LOT-24135 · R-88",
                  "occurredAt":"2026-08-08T11:58:03.412765"}]
                """).get(0);
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

    @Test
    void 백건은_건당_한_번이_아니라_묶음으로_묻는다() {
        FakeLlm llm = new FakeLlm();
        List<FormattedMessage> out = MessageJudge.judgeAll(llm, dumps(100), false);

        assertThat(out).hasSize(100);
        // 100회가 아니라 ceil(100/10) = 10회.
        assertThat(llm.calls).hasSize(100 / MessageJudge.BATCH_SIZE);
        assertThat(llm.calls).allSatisfy(c -> assertThat(c).hasSizeLessThanOrEqualTo(
                MessageJudge.BATCH_SIZE));
        // 순서와 원문은 조각 그대로 — 화면이 이 순서로 카드를 세운다.
        assertThat(out.get(0).raw()).startsWith("Msg0{");
        assertThat(out.get(99).raw()).startsWith("Msg99{");
    }

    @Test
    void 못_받은_조각만_반씩_좁혀_다시_묻는다() {
        FakeLlm llm = new FakeLlm();
        llm.flaky.add(3);
        List<FormattedMessage> out = MessageJudge.judgeAll(llm, dumps(10), false);

        assertThat(out).hasSize(10);
        assertThat(out.get(3).json()).isNotNull();
        // 첫 묶음 + 반쪽 둘 + 한 건 — 열 건을 1건씩 다시 묻지 않는다.
        assertThat(llm.calls).hasSizeLessThanOrEqualTo(5);
        assertThat(llm.calls.get(llm.calls.size() - 1)).containsExactly(3);
    }

    @Test
    void 끝내_못_받은_조각은_원문만_달고_자리를_지킨다() {
        FakeLlm llm = new FakeLlm();
        llm.drop = java.util.Set.of(1);
        List<FormattedMessage> out = MessageJudge.judgeAll(llm, dumps(3), false);

        assertThat(out).hasSize(3);
        assertThat(out.get(1).json()).isNull();
        // 100건이 한 건 때문에 통째로 사라지지 않는다 — 원문은 남는다.
        assertThat(out.get(1).raw()).startsWith("Msg1{");
        assertThat(out.get(0).json()).isNotNull();
        assertThat(out.get(2).json()).isNotNull();
    }

    @Test
    void 후보가_아니거나_상한을_넘으면_빈_목록이다() {
        FakeLlm llm = new FakeLlm();
        assertThat(MessageJudge.judgeAll(llm, "COL_A\tCOL_B\n1\t2", false)).isEmpty();
        assertThat(MessageJudge.judgeAll(llm, dumps(MessageJudge.MAX_CHUNKS + 1), true)).isEmpty();
        // 후보가 아니면 LLM 을 부르지 않는다 — 스니프의 존재 이유가 그것이다.
        assertThat(llm.calls).isEmpty();
    }
}

package fdc.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillSpec;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 분기 판정 프롬프트(#55) — 문안 정본 {@code docs/branch-prompt.md} 의 결정론
 * 조립과, 보수 강등이 규율인 응답 파싱.
 */
class BranchPromptTest {

    /** 실 spec(fdc-trace-reading) 골격 + 열림형 분기 하나를 더한 픽스처. */
    private static SkillSpec spec() {
        return new SkillSpec("fdc-trace-reading", null,
                new SkillSpec.SkillScope("센서", "단일", "생성 이력"),
                "센서 측정값", "FDC_SENSOR_READING", null,
                List.of(new SkillSpec.SkillInput("equipment", true, "설비"),
                        new SkillSpec.SkillInput("param_index", true, "파라미터"),
                        new SkillSpec.SkillInput("start", true, "시작"),
                        new SkillSpec.SkillInput("end", true, "끝")),
                null,
                List.of(
                        new SkillSpec.SkillStep("1단계 — 구간 측정 집계", "fdc_sensor_reading",
                                "측정 분포", null,
                                "SELECT COUNT(*) AS CNT FROM fdc_sensor_reading WHERE eqp_id = :eqp",
                                Map.of("eqp", new SkillSpec.BindSource("arg", "equipment", null, null)),
                                List.of(
                                        new SkillSpec.SkillBranch("CNT = 0",
                                                "종료하고 \"그 기간·PARAM_INDEX로는 측정이 없다\"로 답한다", null),
                                        new SkillSpec.SkillBranch("이탈(ANOM)이 유의미하게 섞여 있으면",
                                                "이탈 상세를 본다", 1)),
                                null),
                        new SkillSpec.SkillStep("2단계 — 이탈 상세 조회", null, "이탈 상세", null,
                                "SELECT read_time FROM fdc_sensor_reading WHERE eqp_id = :eqp",
                                Map.of("eqp", new SkillSpec.BindSource("arg", "equipment", null, null)),
                                null, null)),
                null, null);
    }

    private static List<QueryPool.Query> steps() {
        return QueryPool.of(List.of(spec())).stepsOf("fdc-trace-reading");
    }

    private static ChatDataSnapshot aggregate(String cnt, String anom) {
        return new ChatDataSnapshot("k", "1단계", "2026-08-03T00:00",
                List.of("CNT", "MEAN", "SD", "MINV", "MAXV", "ANOM"), 1,
                List.of(Arrays.asList(cnt, null, null, null, null, anom)));
    }

    private static String body(ChatDataSnapshot data) {
        List<QueryPool.Query> steps = steps();
        return BranchPrompt.body(spec(), "fdc-trace-reading",
                Map.of("equipment", "CVD-01", "param_index", "7",
                        "start", "2026-07-01", "end", "2026-07-31"),
                steps.get(0), steps, data);
    }

    @Test
    void 판정_본문이_문서_문안대로_결정론_조립된다() {
        String body = body(aggregate("0", "0"));

        assertThat(body).startsWith(BranchPrompt.SECTION_HEAD);
        assertThat(body).contains("- 스킬: fdc-trace-reading (센서 측정값)");
        // 인자는 키 정렬 — 같은 run 은 항상 같은 문장.
        assertThat(body).contains(
                "- 시작 인자: end=2026-07-31, equipment=CVD-01, param_index=7, start=2026-07-01");
        assertThat(body).contains("- 도착한 단계: 1단계 \"구간 측정 집계\"");
        assertThat(body).contains("## 1단계 결과");
        assertThat(body).contains("| CNT | MEAN | SD | MINV | MAXV | ANOM |");
        assertThat(body).contains("| 0 | (null) | (null) | (null) | (null) | 0 |");
        assertThat(body).contains("## 분기 목록 (0부터 번호)");
        assertThat(body).contains(
                "0. 조건: CNT = 0 → 효과: 종료 — 종료하고 \"그 기간·PARAM_INDEX로는 측정이 없다\"로 답한다");
        assertThat(body).contains(
                "1. 조건: 이탈(ANOM)이 유의미하게 섞여 있으면 → 효과: 열림 — 2단계 \"이탈 상세 조회\" 를 연다");
        assertThat(body).contains("{\"decision\":\"stop\",\"index\":<번호>,\"reason\":\"<근거 한 줄>\"}");
        assertThat(body).contains("{\"decision\":\"continue\"}");
        assertThat(body).contains("조건 문장은 정해진 문법 없이 사람 말로 적혀 있다");
    }

    @Test
    void 성립_응답은_Call_로_돌아온다() {
        List<SkillSpec.SkillBranch> branches = steps().get(0).branches();

        BranchPrompt.Call stop = BranchPrompt.parse(
                "{\"decision\":\"stop\",\"index\":0,\"reason\":\"CNT가 0건입니다.\"}", branches);
        assertThat(stop).isNotNull();
        assertThat(stop.decision()).isEqualTo("stop");
        assertThat(stop.index()).isZero();
        assertThat(stop.reason()).isEqualTo("CNT가 0건입니다.");

        BranchPrompt.Call open = BranchPrompt.parse(
                "{\"decision\":\"open\",\"index\":1,\"reason\":\"이탈이 있습니다.\"}", branches);
        assertThat(open).isNotNull();
        assertThat(open.decision()).isEqualTo("open");
        assertThat(open.index()).isEqualTo(1);
    }

    @Test
    void 코드펜스로_감싼_흔한_위반은_흡수한다() {
        BranchPrompt.Call call = BranchPrompt.parse(
                "```json\n{\"decision\":\"stop\",\"index\":0,\"reason\":\"r\"}\n```",
                steps().get(0).branches());
        assertThat(call).isNotNull();
        assertThat(call.decision()).isEqualTo("stop");
    }

    @Test
    void 계약_밖_응답은_전부_continue_강등이다() {
        List<SkillSpec.SkillBranch> branches = steps().get(0).branches();

        // continue 자체, JSON 아님, decision 낯섦, index 범위 밖.
        assertThat(BranchPrompt.parse("{\"decision\":\"continue\"}", branches)).isNull();
        assertThat(BranchPrompt.parse("조건이 성립하는 것 같습니다.", branches)).isNull();
        assertThat(BranchPrompt.parse("{\"decision\":\"branch\",\"index\":0}", branches)).isNull();
        assertThat(BranchPrompt.parse("{\"decision\":\"stop\",\"index\":9}", branches)).isNull();
        assertThat(BranchPrompt.parse(null, branches)).isNull();

        // spec 효과와 어긋난 종류 — 종료형 분기에 open, 열림형 분기에 stop.
        assertThat(BranchPrompt.parse("{\"decision\":\"open\",\"index\":0}", branches)).isNull();
        assertThat(BranchPrompt.parse("{\"decision\":\"stop\",\"index\":1}", branches)).isNull();
    }
}

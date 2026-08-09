package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.contract.ScreenMap;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.screens.ScreenClassifier;
import fdc.agent.screens.ScreenClassifier.Classification;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 화면 분류 코어(#63) — 닫힌 목록 강제와 ④ group-by 투영을 검증한다. vision
 * 호출 자체는 seam({@link LlmClient}) 뒤라 실 LLM 없이 응답 문자열만 갈아끼운다.
 */
class ScreenClassifierTest {

    private static final ScreenMap A = new ScreenMap("a", "센서값 이력 조회", "FDC Monitor",
            List.of("이력조회"), List.of("표 형태"), null);
    private static final ScreenMap B = new ScreenMap("b", "트렌드 조회", "FDC Monitor",
            List.of("트렌드"), List.of("차트"), null);
    private static final ScreenMap C = new ScreenMap("c", "관리도 조회", "SPC",
            List.of("관리도"), List.of("UCL/LCL"), null);

    private static ScreenClassifier classifierReturning(String json) {
        LlmClient stub = (messages, tools) -> new LlmTurn.Final(json);
        return new ScreenClassifier(stub, () -> List.of(A, B, C));
    }

    @Test
    void 카탈로그에_있는_id만_후보로_남긴다() {
        ScreenClassifier classifier = classifierReturning(
                "{\"candidates\":[{\"id\":\"a\",\"reason\":\"탭 활성화\"},"
                        + "{\"id\":\"ghost\",\"reason\":\"지어낸 화면\"}]}");
        Classification result = classifier.classify("data:image/png;base64,AAAA");

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).id()).isEqualTo("a");
        assertThat(result.candidates().get(0).menuLabel()).isEqualTo("FDC Monitor › 이력조회");
    }

    @Test
    void 전부_버려지면_후보_0개다() {
        ScreenClassifier classifier = classifierReturning(
                "{\"candidates\":[{\"id\":\"ghost\",\"reason\":\"없음\"}]}");
        assertThat(classifier.classify("data:image/png;base64,AAAA").candidates()).isEmpty();
    }

    @Test
    void 같은_id를_중복_지목해도_카드_한_칸만_채운다() {
        ScreenClassifier classifier = classifierReturning(
                "{\"candidates\":[{\"id\":\"a\",\"reason\":\"1\"},{\"id\":\"a\",\"reason\":\"또\"},"
                        + "{\"id\":\"b\",\"reason\":\"2\"}]}");
        Classification result = classifier.classify("data:image/png;base64,AAAA");
        assertThat(result.candidates()).extracting(ScreenClassifier.Candidate::id)
                .containsExactly("a", "b");
    }

    @Test
    void 후보가_2개만_남아도_성립한다() {
        ScreenClassifier classifier = classifierReturning(
                "{\"candidates\":[{\"id\":\"a\",\"reason\":\"1\"},{\"id\":\"b\",\"reason\":\"2\"}]}");
        assertThat(classifier.classify("data:image/png;base64,AAAA").candidates()).hasSize(2);
    }

    @Test
    void program으로_group_by한다() {
        Classification result = classifierReturning("{\"candidates\":[]}")
                .classify("data:image/png;base64,AAAA");

        assertThat(result.byProgram().keySet()).containsExactly("FDC Monitor", "SPC");
        assertThat(result.byProgram().get("FDC Monitor")).containsExactly(A, B);
        assertThat(result.byProgram().get("SPC")).containsExactly(C);
    }

    @Test
    void program_표기가_다르면_그룹이_갈라진다() {
        ScreenMap spaced = new ScreenMap("d", "다른표기", "FDC Monitor ", null, null, null);
        ScreenClassifier classifier = new ScreenClassifier(
                (messages, tools) -> new LlmTurn.Final("{\"candidates\":[]}"),
                () -> List.of(A, spaced));

        Classification result = classifier.classify("data:image/png;base64,AAAA");
        assertThat(result.byProgram().keySet()).containsExactly("FDC Monitor", "FDC Monitor ");
    }
}

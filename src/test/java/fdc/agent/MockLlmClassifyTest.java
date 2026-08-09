package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.llm.MockLlm;
import fdc.agent.screens.ScreenClassifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MockLlm 의 화면 분류 결정론 응답(#63) — 실 vision 없이 카탈로그 앞 3개 id 를
 * 후보로 돌려주는지 확인한다(제한망 데모 경로).
 */
class MockLlmClassifyTest {

    @Test
    void 분류_지시_상수를_감지하면_카탈로그_앞_3개_id를_돌려준다() {
        String prompt = ScreenClassifier.CLASSIFY_INSTRUCTION + """

                화면 목록:
                - a: 센서값 이력 조회 (FDC Monitor › 이력조회)
                - b: 트렌드 조회 (FDC Monitor › 트렌드)
                - c: 관리도 조회 (SPC › 관리도)
                - d: 여분 화면 (SPC › 기타)
                """;
        LlmTurn turn = new MockLlm().next(List.of(LlmMessage.of(Role.USER, prompt)), List.of());

        assertThat(turn).isInstanceOf(LlmTurn.Final.class);
        String content = ((LlmTurn.Final) turn).content();
        assertThat(content).contains("\"id\":\"a\"", "\"id\":\"b\"", "\"id\":\"c\"")
                .doesNotContain("\"id\":\"d\"");
    }
}

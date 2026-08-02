package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * 분기 판정 사실 하나(#55) — LLM 이 spec {@code branches} 의 성립을 판정한 결과다.
 * FE 가 로컬로 재현할 수 없는 유일한 판정이라(LLM), done 으로 내려가 분석 카드에
 * <b>도착한 사실</b>로 저장되고, FE 는 다음 {@code /chat/data} body 의
 * {@code branchDecisions} 로 되보낸다 — 판정기는 무상태를 유지한다(picks 와 같은
 * echo 규율).
 *
 * <p>{@code decision} 은 {@code "stop"}(절차 종결) 또는 {@code "open"}(분기의
 * {@code opens} 스텝이 열림). continue 는 사실이 아니므로 실리지 않는다 — 판정
 * 트리거가 이벤트(그 스텝의 도착)라, 같은 데이터로 다시 물을 일이 없다.
 *
 * @param skill spec 이름 (예: fdc-trace-reading)
 * @param args run 시작 인자 원문 — run 정체(skill+argsPart)를 맞추는 재료
 * @param step 분기가 붙은 스텝(0-기반) — 판정 근거 데이터가 도착한 자리
 * @param decision "stop" | "open"
 * @param index 성립한 분기의 {@code branches[]} 번호(0-기반)
 * @param reason LLM 근거 한 문장 — 화면·트레이스 표시용
 */
public record BranchDecision(
        String skill,
        Map<String, String> args,
        int step,
        String decision,
        int index,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {

    @JsonIgnore
    public boolean isStop() {
        return "stop".equals(decision);
    }

    @JsonIgnore
    public boolean isOpen() {
        return "open".equals(decision);
    }
}

package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * BE 가 "이 질문에 답하려면 이 값 하나가 필요한데 아직 없다"를 알리는 입력 요청.
 * done 페이로드의 {@code inputRequests} 로 나가면 FE 데이터 패널이 <b>입력 카드</b>로
 * 렌더한다 — 사용자가 값을 채워 다음 요청의 {@code inputs} 맵에 실어 보내면, 그 값으로
 * 스킬 인자가 채워져 조회를 이어간다.
 *
 * <p>{@link DataRequest}(SQL 실행→표 붙여넣기)와 형제지만 다른 종류다 — 이쪽은
 * 스칼라 값 하나(예: param_index)를 직접 타이핑해 받는다. 지금은 텍스트만,
 * 이미지·raw 데이터 포용은 후속 이슈.
 *
 * <p>{@code skill} 은 이 입력이 어느 스킬 것인지 태그다 — 회신은 스킬로 네임스페이스돼
 * ({@code inputs[skill][key]}) 같은 key 를 쓰는 여러 스킬이 충돌하지 않는다. 지금은
 * 한 번에 한 스킬만 진행하지만(단일), 스키마는 멀티-스킬 대비로 태그를 싣는다.
 * {@code key} 는 스킬 인자 이름과 1:1 이라 회신된 {@code inputs[skill][key]} 로 곧장
 * 바인딩된다. {@code description} 은 선택 — 없으면 JSON 에서 생략한다.
 *
 * <p>{@code type} 은 spec 이 선언한 입력 위젯 신호({@code datetime | date}, 없으면
 * 자유 텍스트) — FE 입력 카드가 이 값으로 캘린더를 붙인다. LLM 인자가 아니라
 * {@link fdc.agent.chat.InputRequestTool} 이 (skill, key) 로 spec 에서 결정론으로
 * 찾아 싣는다.
 */
public record InputRequest(
        String skill,
        String key,
        String label,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) String type) {

    public InputRequest(String skill, String key, String label, String description) {
        this(skill, key, label, description, null);
    }
}

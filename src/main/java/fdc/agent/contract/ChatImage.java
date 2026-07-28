package fdc.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 답에 딸려 나가는 그림 — 계측 화면 캡처·설비 도면 같은 것.
 *
 * <p>실제로 어디서 읽어오는지는 이쪽 관심이 아니다(MCP 도구 등). 여기는 <b>모양만</b>
 * 정한다: 원격 주소({@code url})와 인라인 base64({@code dataUrl}) 둘 다 받는다 —
 * 사내 이미지 서버가 있으면 url, 없으면 inline 이 된다. 둘 다 없으면 FE 가 "주소
 * 없음"으로 그린다(빈 카드를 그리는 대신 왜 안 보이는지 말한다).
 */
public record ChatImage(
        String label,
        @JsonInclude(JsonInclude.Include.NON_NULL) String dataUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) String url,
        @JsonInclude(JsonInclude.Include.NON_NULL) String alt) {
}

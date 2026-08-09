package fdc.agent.contract;

import java.util.List;

/**
 * POST /api/fdc/v1/chat/image 응답 — 캡처 한 장의 화면 분류 결과(#63).
 *
 * <p>카드가 그릴 것을 그대로 편다(demo-fe#189 가 와이어 계약의 진실원):
 * {@code candidates} 는 카드 행 1~3, {@code browse} 는 ④ "직접 목록에서 선택" 이
 * 펼치는 2열 브라우저의 재료다(왼쪽 {@code group} = program, 오른쪽 {@code options}).
 *
 * <p><b>항상 200 이다.</b> 분류가 실패하거나 후보가 전멸해도 {@code candidates} 가
 * 빈 배열로 나가고 {@code browse} 는 카탈로그로 채워진다 — 화면은 ④⑤ 만 남은 카드로
 * 강등될 뿐 사람이 진행할 길이 끊기지 않는다. 확정은 언제나 사용자 클릭이므로
 * 후보가 없다는 것이 곧 막힘은 아니다.
 *
 * <p>질문 문구·⑤ 문구는 FE 상수라 여기 안 싣는다 — 화면 문구를 와이어에 실으면
 * 문구를 고칠 때마다 BE 배포가 걸린다.
 */
public record ScreenClassification(List<ScreenOption> candidates, List<BrowseGroup> browse) {

    /**
     * 고를 수 있는 화면 하나. {@code value} 는 screen-map id(닫힌 목록의 어휘) —
     * 후보로 고르든 ④ 목록에서 고르든 같은 값이라, 하류(기대 컬럼 주입 추출)는
     * 어느 경로로 확정됐는지 몰라도 된다.
     */
    public record ScreenOption(String value, String label, String caption) {
    }

    /** ④ 브라우저의 왼쪽 열 하나 — program 과 그 program 의 화면들. */
    public record BrowseGroup(String group, List<ScreenOption> options) {
    }
}

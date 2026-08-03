package fdc.agent.contract;

import java.util.List;

/**
 * 데이터 패널 스냅샷 한 건의 <b>rows 없는 요약</b> — panel-judge 판정의 집합
 * 원소다(#38 T16). 패널 클릭 한 번에 rows 전문을 다시 싣지 않기 위한 경량
 * 계약으로, rows 가 실제로 쓰이는 스냅샷(종결 서술의 전량 동봉)만
 * {@code snapshots} 로 따라온다.
 *
 * <p>{@code rowCount} 의 세 상태는 {@link ChatDataSnapshot} 과 같다 —
 * null=아직 결과 안 옴(카탈로그), 0=<b>0행으로 확인됨</b>(없다는 사실),
 * &gt;0=행 있음.
 *
 * <p>{@code included} 는 화면의 체크 상태다(#38 T4) — 판정 집합은 "패널에 있는
 * 전부"가 아니라 <b>사용자가 켜 둔 것</b>이고, 그 축을 계약에 명시해 화면과
 * 판정이 조용히 어긋나지 않게 한다. null 은 true 로 본다(관용).
 *
 * <p>{@code contentHash} 는 스냅샷 내용 지문 — 이 PR 에서는 echo 만 하고,
 * 재전송 해소 캐시(#41)가 같은 채널을 이어받는다.
 */
public record SnapshotIndexEntry(
        String queryKey,
        String label,
        String capturedAt,
        List<String> columns,
        Integer rowCount,
        String contentHash,
        Boolean included) {

    /** 결과가 도착했나(행이 있든 0행 확인이든) — {@link ChatDataSnapshot#arrived()} 대응. */
    public boolean arrived() {
        return rowCount != null;
    }

    /** 조회 결과가 0행임이 확인됐다 — 절차는 여기서 끝난다. */
    public boolean isEmptyResult() {
        return rowCount != null && rowCount == 0;
    }

    /** 판정 집합에 드는가 — 체크 해제된 항목은 판정 밖이다(T4). */
    public boolean isIncluded() {
        return included == null || included;
    }
}

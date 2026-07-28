package fdc.agent.contract;

import java.util.List;

/**
 * FE 데이터 패널이 채팅 요청 본문(dataSnapshots)에 실어 보내는 스냅샷 한 건
 * (demo-fe {@code ChatDataSnapshot} 계약과 1:1). DB 에 붙지 못하는 환경에서
 * 사용자가 직접 조회해 붙여넣은 표다.
 *
 * <p><b>세 상태를 구분한다</b> — 이게 이 계약의 핵심이다:
 *
 * <ul>
 *   <li>{@link #hasRows()} — 내용이 왔다. 임시 DB 로 적재돼 {@code query_snapshot} 으로 닿는다.
 *   <li>{@link #isEmptyResult()} — <b>조회했더니 0행이었다</b>. 이것도 사실이고, "그 데이터는
 *       없다"는 답의 근거이며, 그 조회 절차는 여기서 끝난다.
 *   <li>{@link #isCatalogOnly()} — 아직 안 왔다. "이런 표가 있다"만 알리고 다시 요청할 수 있다.
 * </ul>
 *
 * <p>0행과 미첨부를 뭉개면, 조회해서 없음을 확인한 사용자에게 같은 요청이 다시 나가거나
 * 모델이 오지 않을 데이터를 기다린다. 판정은 {@code rows} 가 먼저다 — FE 는 등록된
 * 스냅샷의 {@code rows} 를 항상 채워 보내므로 빈 배열은 "0행으로 등록했다"는 뜻이다.
 * {@code rows} 를 아예 안 싣는 클라이언트를 위해 {@code rowCount == 0} 도 같게 본다.
 *
 * <p>cell 은 원문 NULL 을 나타내는 {@code null} 을 담을 수 있다({@code (string|null)[][]}).
 * 계약은 느슨하게 수용한다 — 빠진 필드는 null.
 */
public record ChatDataSnapshot(
        String queryKey,
        String label,
        String capturedAt,
        List<String> columns,
        Integer rowCount,
        List<List<String>> rows) {

    /** 내용이 왔고 행이 있다. */
    public boolean hasRows() {
        return rows != null && !rows.isEmpty();
    }

    /** 조회 결과가 0행임이 확인됐다 — 미첨부가 아니라 <b>없다는 사실</b>이다. */
    public boolean isEmptyResult() {
        if (rows != null) {
            return rows.isEmpty();
        }
        return rowCount != null && rowCount == 0;
    }

    /** 아직 내용이 안 온 카탈로그 항목 — 다시 요청할 수 있다. */
    public boolean isCatalogOnly() {
        return !hasRows() && !isEmptyResult();
    }

    /** 결과가 도착했나(행이 있든, 0행으로 확인됐든) — 재요청 억제의 기준. */
    public boolean arrived() {
        return hasRows() || isEmptyResult();
    }
}

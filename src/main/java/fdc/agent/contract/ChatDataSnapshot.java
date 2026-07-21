package fdc.agent.contract;

import java.util.List;

/**
 * FE 데이터 패널이 채팅 요청 본문(dataSnapshots)에 실어 보내는 스냅샷 한 건
 * (demo-fe {@code ChatDataSnapshot} 계약과 1:1). DB 에 붙지 못하는 환경에서
 * 사용자가 직접 조회해 붙여넣은 표다.
 *
 * <p>{@code rows} 는 📌(pinned)인 스냅샷만 채워진다 — 있으면 전문(내용 푸시),
 * 없으면 카탈로그 항목("이런 표가 있다"만 알리고 내용은 아직 안 온 상태)이다.
 * cell 은 원문 NULL 을 나타내는 {@code null} 을 담을 수 있다({@code (string|null)[][]}).
 * 계약은 느슨하게 수용한다 — 빠진 필드는 null.
 */
public record ChatDataSnapshot(
        String queryKey,
        String label,
        String capturedAt,
        List<String> columns,
        Integer rowCount,
        List<List<String>> rows) {
}

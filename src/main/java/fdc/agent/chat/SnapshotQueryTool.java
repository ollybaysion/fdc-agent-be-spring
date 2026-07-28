package fdc.agent.chat;

import fdc.agent.contract.ChatTable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code query_snapshot} — 사용자가 붙여넣어 임시 DB(SQLite)로 적재된 표를 SELECT 로
 * 조회하는 조회 툴(Design B). 붙여넣은 행은 프롬프트에 붓지 않고 이 툴로만 닿는다 —
 * 스키마 카탈로그만 맥락 섹션에 실리고, 값은 조회해서 근거로 삼는다.
 *
 * <p>결과 표는 done 페이로드에 실리고 요약이 LLM 에 되먹인다(다른 조회 툴과 같은 경로).
 * SELECT-only 는 {@link SnapshotDb} 가 강제한다 — 변경 문은 여기까지 오지 않는다.
 *
 * <p>이 툴은 붙여넣은 표가 있을 때만 등록된다. 사용 규율({@link #guidance()})도 같이
 * 붙었다 빠지므로, 적재된 표가 없는 요청의 프롬프트에는 이 툴 이야기가 아예 없다.
 */
public final class SnapshotQueryTool implements AgentTool {

    public static final String NAME = "query_snapshot";

    private final SnapshotDb db;

    public SnapshotQueryTool(SnapshotDb db) {
        this.db = db;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "사용자가 붙여넣어 임시 DB(SQLite)로 적재된 표를 SELECT 로 조회한다. "
                + "INSERT/UPDATE/DROP 등 변경은 불가(SELECT 만).";
    }

    @Override
    public String guidance() {
        return "사용자가 붙여넣어 임시 DB(SQLite)로 적재된 표가 있으면, 값을 추측하지 말고 " + NAME
                + " 툴에 SELECT 문을 주어 조회한 결과를 근거로 삼는다"
                + "(사용 가능한 테이블·컬럼은 [제공된 데이터]에 있다).";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sql", ToolArgs.string(
                "실행할 SELECT 문. 사용 가능한 테이블·컬럼은 [제공된 데이터]에 있다. "
                        + "SELECT/WITH 로 시작하는 단일 문장만 허용된다."));
        props.put("title", ToolArgs.string("결과 표에 붙일 제목(선택)."));
        return ToolArgs.schema(props, List.of("sql"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String sql = ToolArgs.text(args, "sql");
        if (sql == null) {
            return ToolResult.of("sql 인자가 필요합니다(조회할 SELECT 문).");
        }
        try {
            ChatTable table = db.query(sql, ToolArgs.text(args, "title"));
            String cols = table.columns() != null ? String.join(", ", table.columns()) : "";
            return new ToolResult(
                    NAME + ": " + table.rows().size() + "행 조회됨 (컬럼: " + cols + ").",
                    List.of(table));
        } catch (IllegalArgumentException e) {
            // 가드 위반·SQL 오류는 사유를 되먹인다 — 모델이 문장을 고쳐 다시 시도할 수 있게.
            return ToolResult.of("조회할 수 없습니다: " + e.getMessage()
                    + " SELECT 문만 허용되며, 사용 가능한 테이블·컬럼은 [제공된 데이터]에 있습니다.");
        }
    }
}

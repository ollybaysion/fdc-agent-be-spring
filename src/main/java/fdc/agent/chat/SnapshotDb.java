package fdc.agent.chat;

import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.ChatTable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 붙여넣은 데이터 스냅샷(행 있는 📌)을 요청 단위 인메모리 SQLite 로 굽는다(Design B).
 * 프롬프트에 행을 다 붓는 대신(Design A) 스키마만 주입하고, 에이전트는
 * {@code query_snapshot} 툴로 SELECT 만 실행해 필요한 값을 정확히 뽑는다.
 *
 * <p>엔진(data-provisioning)의 {@code render}(SQLite) 대신 BE 가 자체 경로로 굽기로 결정
 * (2026-07-22) — FE 브라우저 포트가 이미 파싱을 끝내 {@link ChatDataSnapshot}(columns+rows)이
 * 구조화된 채 도착하므로 엔진 {@code ingest} 는 여기서 불필요하다. 원문 보존을 위해 모든
 * 컬럼은 {@code TEXT}. 식별자(테이블·컬럼명)는 안전 문자셋으로 슬러그해 DDL 인젝션 표면을
 * 없애고, 조회는 SELECT-only 로 잠근다({@code PRAGMA query_only} + 문장 가드 이중).
 *
 * <p>인스턴스는 한 요청에 묶이며 {@link #close()} 로 연결을 닫는다(요청 끝나면 폐기).
 */
public final class SnapshotDb implements AutoCloseable {

    /** query_snapshot 한 번이 프롬프트로 되먹이는 최대 행수(큰 결과가 맥락을 삼키지 않게). */
    static final int MAX_RESULT_ROWS = 200;

    /** 적재된 표 하나의 메타(카탈로그·검증용). name/columns 는 슬러그된 조회용 식별자. */
    record Table(String name, String label, List<String> columns, int rowCount) {
    }

    private final Connection conn;
    private final List<Table> tables;

    private SnapshotDb(Connection conn, List<Table> tables) {
        this.conn = conn;
        this.tables = tables;
    }

    public boolean isEmpty() {
        return tables.isEmpty();
    }

    List<Table> tables() {
        return tables;
    }

    /**
     * 행 있는 스냅샷들을 인메모리 SQLite 로 적재한다. 행 없는(카탈로그) 항목은 담을 게
     * 없으므로 건너뛴다 — 담을 표가 하나도 없으면 {@code null}(툴·스키마 주입 안 함).
     * 개별 스냅샷 적재 실패는 그 스냅샷만 건너뛴다(사용자 붙여넣기라 관대하게).
     */
    public static SnapshotDb build(List<ChatDataSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        List<ChatDataSnapshot> rowBearing = new ArrayList<>();
        for (ChatDataSnapshot s : snapshots) {
            if (s != null && s.rows() != null && !s.rows().isEmpty()) {
                rowBearing.add(s);
            }
        }
        if (rowBearing.isEmpty()) {
            return null;
        }
        Connection conn;
        try {
            conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        } catch (SQLException e) {
            throw new IllegalStateException("스냅샷 DB 를 열 수 없습니다: " + e.getMessage(), e);
        }
        List<Table> tables = new ArrayList<>();
        Set<String> usedTableNames = new LinkedHashSet<>();
        try {
            for (ChatDataSnapshot s : rowBearing) {
                try {
                    tables.add(loadTable(conn, s, usedTableNames));
                } catch (SQLException perSnapshot) {
                    // 이 스냅샷만 건너뛴다 — 나머지는 계속 적재.
                }
            }
            if (tables.isEmpty()) {
                conn.close();
                return null;
            }
            // 적재(쓰기)가 끝난 뒤 연결을 읽기 전용으로 잠근다(문장 가드의 이중 방어).
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA query_only = ON");
            }
        } catch (SQLException e) {
            closeQuietly(conn);
            throw new IllegalStateException("스냅샷 DB 적재 실패: " + e.getMessage(), e);
        }
        return new SnapshotDb(conn, tables);
    }

    /** 스냅샷 한 건 → 테이블 1개 생성 + 행 적재. 슬러그된 식별자만 DDL 에 넣는다. */
    private static Table loadTable(Connection conn, ChatDataSnapshot s, Set<String> usedTableNames)
            throws SQLException {
        String rawKey = s.queryKey() != null && !s.queryKey().isBlank()
                ? s.queryKey()
                : (s.label() != null ? s.label() : "snapshot");
        String tableName = uniqueName(slugIdent(rawKey, "snapshot"), usedTableNames);
        usedTableNames.add(tableName);

        List<String> rawCols = s.columns() != null ? s.columns() : List.of();
        List<String> colNames = new ArrayList<>();
        Set<String> usedCols = new LinkedHashSet<>();
        for (int i = 0; i < rawCols.size(); i++) {
            String col = uniqueName(slugIdent(rawCols.get(i), "col_" + (i + 1)), usedCols);
            usedCols.add(col);
            colNames.add(col);
        }
        if (colNames.isEmpty()) {
            // 컬럼 헤더가 없으면 행 폭에서 열 수를 추정.
            int width = s.rows().stream().mapToInt(r -> r != null ? r.size() : 0).max().orElse(0);
            for (int i = 0; i < width; i++) {
                colNames.add("col_" + (i + 1));
            }
        }

        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(quote(tableName)).append(" (");
        for (int i = 0; i < colNames.size(); i++) {
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(quote(colNames.get(i))).append(" TEXT");
        }
        ddl.append(")");
        try (Statement st = conn.createStatement()) {
            st.execute(ddl.toString());
        }

        StringBuilder ins = new StringBuilder("INSERT INTO ").append(quote(tableName)).append(" VALUES (");
        for (int i = 0; i < colNames.size(); i++) {
            ins.append(i > 0 ? ", ?" : "?");
        }
        ins.append(")");
        int loaded = 0;
        try (PreparedStatement ps = conn.prepareStatement(ins.toString())) {
            for (List<String> row : s.rows()) {
                for (int i = 0; i < colNames.size(); i++) {
                    String cell = row != null && i < row.size() ? row.get(i) : null;
                    ps.setString(i + 1, cell); // null → SQL NULL(원문 NULL 보존)
                }
                ps.addBatch();
                loaded++;
            }
            ps.executeBatch();
        }

        String label = s.label() != null && !s.label().isBlank() ? s.label() : rawKey;
        return new Table(tableName, label, colNames, loaded);
    }

    /**
     * 프롬프트에 주입할 스키마 카탈로그(행 데이터는 빼고 테이블·컬럼·행수만).
     * 테이블명은 백틱으로 감싸 조회 시 그대로 쓸 수 있게 한다.
     */
    public String schemaCatalog() {
        List<String> lines = new ArrayList<>();
        for (Table t : tables) {
            lines.add("- `" + t.name() + "` — " + t.label() + ", " + t.rowCount() + "행"
                    + ", 컬럼: " + String.join(", ", t.columns()));
        }
        return String.join("\n", lines);
    }

    /**
     * SELECT-only SQL 을 실행해 결과를 표로. 문장 가드(단일 SELECT/WITH)를 통과해야 하며,
     * 연결은 이미 {@code query_only} 라 쓰기는 실행 단계에서도 거부된다. 결과 행은 캡한다.
     *
     * @throws IllegalArgumentException 가드 위반 또는 SQL 오류(에이전트에 되먹일 사유)
     */
    public ChatTable query(String sql, String title) {
        String guarded = requireSelectOnly(sql);
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(guarded)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                cols.add(md.getColumnLabel(i));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next() && rows.size() < MAX_RESULT_ROWS) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    row.put(cols.get(i - 1), rs.getString(i)); // 전부 TEXT — 원문 보존
                }
                rows.add(row);
            }
            return new ChatTable(title != null && !title.isBlank() ? title : "조회 결과", cols, rows);
        } catch (SQLException e) {
            throw new IllegalArgumentException("조회 실패: " + e.getMessage(), e);
        }
    }

    /**
     * SQL 을 SELECT-only 로 검증하고 후행 세미콜론을 정리해 돌려준다. 여러 문장·
     * 비-SELECT 는 거부한다. WITH 로 시작하는 DML(SQLite 는 {@code WITH ... DELETE} 를
     * 허용)은 문자열만으론 못 거르므로 {@code query_only} 연결이 실행 단계에서 막는다.
     */
    static String requireSelectOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 이 비어 있습니다.");
        }
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("SQL 이 비어 있습니다.");
        }
        if (s.contains(";")) {
            throw new IllegalArgumentException("여러 문장은 허용되지 않습니다(단일 SELECT 만).");
        }
        String head = s.toLowerCase();
        if (!head.startsWith("select") && !head.startsWith("with")) {
            throw new IllegalArgumentException("SELECT 문만 허용됩니다.");
        }
        return s;
    }

    /**
     * 식별자를 안전 문자셋 {@code [A-Za-z0-9_]} 으로 슬러그(그 외 문자는 {@code _}).
     * 빈 결과는 fallback, 숫자로 시작하면 {@code t_} 접두. DDL 인젝션 표면을 없앤다.
     */
    static String slugIdent(String raw, String fallback) {
        StringBuilder sb = new StringBuilder();
        if (raw != null) {
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '_';
                sb.append(safe ? c : '_');
            }
        }
        String s = sb.toString();
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '_') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '_') {
            end--;
        }
        s = s.substring(start, end);
        if (s.isEmpty()) {
            s = fallback;
        }
        if (Character.isDigit(s.charAt(0))) {
            s = "t_" + s;
        }
        return s;
    }

    /** 이미 쓴 이름이면 {@code _2}, {@code _3} … 을 붙여 유일하게. */
    private static String uniqueName(String base, Set<String> used) {
        if (!used.contains(base)) {
            return base;
        }
        int n = 2;
        while (used.contains(base + "_" + n)) {
            n++;
        }
        return base + "_" + n;
    }

    /** 슬러그된(안전 문자만) 식별자를 큰따옴표로 감싼다 — 예약어 충돌 방지. */
    private static String quote(String ident) {
        return "\"" + ident + "\"";
    }

    @Override
    public void close() {
        closeQuietly(conn);
    }

    private static void closeQuietly(Connection c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (SQLException ignored) {
            // 폐기 중 오류는 무시.
        }
    }
}

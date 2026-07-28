package fdc.agent.skills;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * spec 의 {@code steps[].sql}({@code :var} 바인드 문장)을 <b>사람이 그대로 실행할 수
 * 있는 한 문장</b>으로 렌더한다 — 값이 리터럴로 박힌 텍스트다.
 *
 * <p>⚠ 이 결과는 BE 가 실행하지 않는다. 요청 카드로 나가 <b>사용자가 사내 DB 에서
 * 자기 권한으로</b> 복붙 실행한다. 그래서 이스케이프는 미관이 아니라 경계다: 여기서
 * 새면 모델이 사람 손을 빌려 SQL 을 주입하는 길이 열린다. 숫자꼴만 맨값으로 두고
 * 나머지는 예외 없이 작은따옴표로 감싸 {@code '} 를 {@code ''} 로 접는다(Oracle 은
 * 기본적으로 백슬래시 이스케이프가 없어 이걸로 충분하다).
 *
 * <p>BE 가 직접 조회하는 경로는 이쪽이 아니다 — 스킬 실행은 {@code JdbcClient} 네임드
 * 바인드로, 붙여넣은 표 조회는 {@link fdc.agent.chat.SnapshotDb}(SELECT-only)로 간다.
 * 셋은 목적이 달라 일부러 따로 산다.
 */
public final class SqlRender {
    private SqlRender() {
    }

    /** 맨값으로 둘 수 있는 숫자꼴 — 그 외는 전부 따옴표. 보수적으로 잡는다. */
    private static final Pattern NUMERIC = Pattern.compile("-?\\d+(\\.\\d+)?");

    private static final Pattern DATE_ONLY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATE_MINUTE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}");
    private static final Pattern DATE_SECOND =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}");

    /** 별칭 없는 항목에서 컬럼 이름으로 인정할 모양 — {@code a} 또는 {@code t.a}. */
    private static final Pattern PLAIN_COLUMN =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*)?");

    private static final Pattern ALIAS_TAIL =
            Pattern.compile("(?is).*\\bAS\\s+\"?([A-Za-z_][A-Za-z0-9_$#]*)\"?\\s*");

    /**
     * {@code :var} 를 값 리터럴로 치환한다. 따옴표 리터럴 안의 {@code :} 는 건드리지
     * 않는다 — 날짜 마스크({@code 'HH24:MI:SS'})가 바인드로 오인되면 안 된다.
     *
     * @throws IllegalArgumentException 값이 없는 바인드가 남았을 때(사유에 이름 나열)
     */
    public static String render(String sql, Map<String, String> binds) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 이 비어 있습니다.");
        }
        Map<String, String> values = binds != null ? binds : Map.of();
        StringBuilder out = new StringBuilder(sql.length() + 32);
        Set<String> missing = new LinkedHashSet<>();
        boolean inQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // '' 는 각각 토글돼 결과적으로 리터럴 안에 머문다(이스케이프된 따옴표).
                inQuote = !inQuote;
                out.append(c);
                continue;
            }
            if (inQuote || c != ':' || i + 1 >= sql.length() || !isIdentStart(sql.charAt(i + 1))) {
                out.append(c);
                continue;
            }
            int end = i + 1;
            while (end < sql.length() && isIdentPart(sql.charAt(end))) {
                end++;
            }
            String name = sql.substring(i + 1, end);
            String value = values.get(name);
            if (value == null) {
                missing.add(name);
                out.append(sql, i, end);
            } else {
                out.append(literal(value));
            }
            i = end - 1;
        }

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("값이 정해지지 않은 바인드가 남았습니다: :"
                    + String.join(", :", missing));
        }
        return out.toString();
    }

    /**
     * 값 하나를 SQL 리터럴로. 숫자꼴은 맨값, ISO 날짜꼴은 {@code TO_DATE}, 나머지는
     * 작은따옴표 + {@code ''} 이스케이프.
     *
     * <p>날짜를 문자열로 두면 Oracle 이 {@code NLS_DATE_FORMAT} 으로 암시 변환을 시도해
     * ORA-01861 로 죽는다(기본 마스크는 {@code DD-MON-RR}). spec 에 타입 힌트가 없어
     * 값 모양으로 추정할 수밖에 없고, DATE 컬럼 비교가 압도적으로 흔하므로 이쪽에
     * 건다. 반대로 날짜 문자열을 담은 VARCHAR 컬럼이면 어긋나는데, 카드의 SQL 은
     * 사람이 보고 고칠 수 있다는 게 이 선택의 안전망이다.
     */
    public static String literal(String value) {
        String v = value.trim();
        if (NUMERIC.matcher(v).matches()) {
            return v;
        }
        if (DATE_SECOND.matcher(v).matches()) {
            return toDate(v, "YYYY-MM-DD HH24:MI:SS");
        }
        if (DATE_MINUTE.matcher(v).matches()) {
            return toDate(v, "YYYY-MM-DD HH24:MI");
        }
        if (DATE_ONLY.matcher(v).matches()) {
            return toDate(v, "YYYY-MM-DD");
        }
        return quote(v);
    }

    private static String toDate(String value, String mask) {
        return "TO_DATE(" + quote(value.replace('T', ' ')) + ", '" + mask + "')";
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * SELECT 목록에서 기대 컬럼을 뽑는다(별칭 우선, 없으면 컬럼 이름). 요청 카드가
     * "무엇이 나와야 하는지"를 보여 주는 용도다.
     *
     * <p><b>자신 없으면 통째로 {@code null}</b> — {@code *} 가 있거나 한 항목이라도 이름을
     * 못 읽으면 반쪽 목록을 내지 않는다. 틀린 기대 컬럼을 카드에 그리는 것이 안 그리는
     * 것보다 나쁘다.
     */
    public static List<String> columnsOf(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String mask = maskLiterals(sql);
        int selectAt = indexOfKeyword(mask, "SELECT", 0);
        // 문장이 SELECT 로 시작할 때만 읽는다 — WITH 절의 안쪽 SELECT 를 바깥 목록으로
        // 착각하면 엉뚱한 컬럼을 카드에 그린다.
        if (selectAt < 0 || !mask.substring(0, selectAt).isBlank()) {
            return null;
        }
        int listStart = selectAt + "SELECT".length();
        int fromAt = indexOfTopLevelKeyword(mask, "FROM", listStart);
        if (fromAt < 0) {
            return null;
        }

        List<String> columns = new ArrayList<>();
        for (int[] span : splitTopLevel(mask, listStart, fromAt)) {
            String item = sql.substring(span[0], span[1]).trim();
            if (item.isEmpty()) {
                return null;
            }
            var alias = ALIAS_TAIL.matcher(item);
            if (alias.matches()) {
                columns.add(alias.group(1).toUpperCase());
                continue;
            }
            if (!PLAIN_COLUMN.matcher(item).matches()) {
                return null; // 별칭 없는 식·별표 — 이름을 지어내지 않는다.
            }
            int dot = item.lastIndexOf('.');
            columns.add(item.substring(dot + 1).toUpperCase());
        }
        return columns.isEmpty() ? null : List.copyOf(columns);
    }

    /** 따옴표 리터럴 내용을 공백으로 덮은 사본 — 위치는 원문과 1:1 이라 substring 이 통한다. */
    private static String maskLiterals(String sql) {
        char[] out = sql.toCharArray();
        boolean inQuote = false;
        for (int i = 0; i < out.length; i++) {
            if (out[i] == '\'') {
                inQuote = !inQuote;
            } else if (inQuote) {
                out[i] = ' ';
            }
        }
        return new String(out);
    }

    /** 괄호 깊이 0 에서 쉼표로 자른 구간들. */
    private static List<int[]> splitTopLevel(String mask, int from, int to) {
        List<int[]> spans = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            char c = mask.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                spans.add(new int[] {start, i});
                start = i + 1;
            }
        }
        spans.add(new int[] {start, to});
        return spans;
    }

    private static int indexOfKeyword(String mask, String keyword, int from) {
        String upper = mask.toUpperCase();
        for (int i = upper.indexOf(keyword, from); i >= 0; i = upper.indexOf(keyword, i + 1)) {
            if (isWordBoundary(upper, i, keyword.length())) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfTopLevelKeyword(String mask, String keyword, int from) {
        String upper = mask.toUpperCase();
        int depth = 0;
        for (int i = from; i <= upper.length() - keyword.length(); i++) {
            char c = upper.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && upper.startsWith(keyword, i)
                    && isWordBoundary(upper, i, keyword.length())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWordBoundary(String text, int at, int length) {
        boolean before = at == 0 || !isIdentPart(text.charAt(at - 1));
        int after = at + length;
        return before && (after >= text.length() || !isIdentPart(text.charAt(after)));
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}

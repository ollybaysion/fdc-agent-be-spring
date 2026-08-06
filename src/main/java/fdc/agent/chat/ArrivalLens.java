package fdc.agent.chat;

import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.SnapshotIndexEntry;
import fdc.agent.skills.NeedsResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 판정기가 도착 데이터를 읽는 창구 — <b>채움 폭포의 1차와 2차</b>.
 *
 * <p>1차는 정확 일치다: 그 need 가 지목한 조달의 키로 등록된 스냅샷. 2차는
 * <b>다른 경로로 온 같은 사실</b>이다 — 우리가 요청한 조회가 아니어도, 같은 대상의
 * 데이터에 그 컬럼이 있으면 그 사실은 채워진 것이다. 조달은 수단이고 need 가
 * 목적이므로, 수단이 달랐다는 이유로 목적이 안 찼다고 하면 사람이 이미 가진 답을
 * 다시 조회하라고 시키게 된다.
 *
 * <p><b>2차는 풀 밖의 표만 본다.</b> 풀 안의 키로 등록된 스냅샷은 어느 조달의
 * 결과인지가 이미 정해져 있고, spec 은 need 마다 <b>(조달, 컬럼)</b> 을 못 박아
 * 두었다 — 같은 스킬의 다른 조달에 이름이 같은 컬럼이 있다고 그것이 같은 사실은
 * 아니다. {@code fdc_sensor.USE_YN}(센서가 쓰이나)과 {@code fdc_equipment.USE_YN}
 * (설비가 쓰이나)이 그 실물이다. 이름으로 이어 버리면 판정이 조용히 틀린 값을
 * 답으로 올린다.
 *
 * <p><b>도착한 1차는 뒤집지 않는다.</b> 지목한 조달이 0행으로 도착했으면 그것으로
 * 끝이고 2차를 보지 않는다 — 0행은 미도착이 아니라 "없다"는 사실이고, 다른 표에
 * 값이 보인다고 그 사실을 갈아치우면 판정이 데이터마다 달라진다.
 *
 * <p><b>대조할 값이 없으면 2차도 없다.</b> run 이름표가 비면(필수 인자가 없는 스킬)
 * 어느 표가 이 절차의 것인지 가릴 근거가 없어 남의 데이터를 끌어올 수 있다.
 * 그때는 1차만 본다.
 */
public final class ArrivalLens implements NeedsResolver.Rows {

    private final String skill;
    private final String argsPart;
    private final Map<String, String> args;
    private final Map<String, SnapshotIndexEntry> index;
    private final Map<String, ChatDataSnapshot> rows;
    private final String without;

    /** {@code 조달id.COLUMN} → 그 값을 실제로 준 스냅샷 키(2차로 채워진 것만). */
    private final Map<String, String> alternates = new LinkedHashMap<>();

    /**
     * @param without 이 키를 미도착으로 치고 본다(서술 전이의 인과 판정). null 이면 전부 본다.
     */
    public ArrivalLens(String skill, String argsPart, Map<String, String> args,
            Map<String, SnapshotIndexEntry> index, Map<String, ChatDataSnapshot> rows,
            String without) {
        this.skill = skill;
        this.argsPart = argsPart != null ? argsPart : "";
        this.args = args != null ? args : Map.of();
        this.index = index;
        this.rows = rows;
        this.without = without;
    }

    /** 2차로 채워진 자리들 — 원장·서술이 "다른 경로로 왔다"를 인용할 근거. */
    public Map<String, String> alternates() {
        return Map.copyOf(alternates);
    }

    @Override
    public NeedsResolver.Cell valuesOf(String queryId, String column) {
        NeedsResolver.Cell exact = exact(queryId, column);
        return exact != null ? exact : alternate(queryId, column);
    }

    /**
     * 1차 — 지목한 조달의 키로 등록된 스냅샷.
     *
     * <p>행 실물이 실려 있으면 그걸로 읽고, 요약만 있으면 <b>"찼지만 값은 모른다"</b>
     * ({@link NeedsResolver.Cell#OPAQUE})로 준다 — 경량 판정(T16)에서도 채워짐은
     * 판정되지만 갈래({@code when})는 못 연다. 요약조차 컬럼을 안 실었으면 행이 온
     * 사실만 믿는다: 컬럼 목록이 없다는 것은 "그 컬럼이 없다"가 아니다.
     */
    private NeedsResolver.Cell exact(String queryId, String column) {
        String key = keyOf(queryId);
        if (key.equals(without)) {
            return null;
        }
        SnapshotIndexEntry hit = index.get(key);
        if (hit == null || !hit.arrived()) {
            return null;
        }
        if (hit.isEmptyResult()) {
            return NeedsResolver.Cell.EMPTY;
        }
        ChatDataSnapshot full = rows.get(key);
        if (full != null && full.hasRows()) {
            return NeedsResolver.Cell.of(QueryProgress.valuesOf(full, column));
        }
        return hasColumn(hit.columns(), column)
                ? NeedsResolver.Cell.OPAQUE
                : NeedsResolver.Cell.EMPTY;
    }

    /**
     * 2차 — 같은 대상의 다른 표에 그 컬럼이 있는가. 값 실물이 있어야 하므로 요약만 온
     * 스냅샷은 대상이 아니다(무엇이 들었는지 모르는 표를 근거로 삼지 않는다).
     */
    private NeedsResolver.Cell alternate(String queryId, String column) {
        if (argsPart.isEmpty()) {
            return null;
        }
        String exactKey = keyOf(queryId);
        for (Map.Entry<String, ChatDataSnapshot> e : rows.entrySet()) {
            String key = e.getKey();
            if (key.equals(exactKey) || key.equals(without)) {
                continue;
            }
            ChatDataSnapshot full = e.getValue();
            if (full == null || !full.hasRows()) {
                continue;
            }
            List<String> values = sameRunValues(key, full, column);
            if (values.isEmpty()) {
                continue;
            }
            alternates.put(queryId + "." + column, key);
            return NeedsResolver.Cell.of(values);
        }
        return null;
    }

    /**
     * 이 표가 같은 대상의 것인가, 그렇다면 그 컬럼의 값은.
     *
     * <p>풀 형식 키는 <b>보지 않는다</b> — 어느 조달의 결과인지가 이미 정해져 있고,
     * spec 이 need 마다 (조달, 컬럼)을 못 박았다. 이름이 같은 남의 컬럼을 끌어오면
     * 조용히 틀린 사실이 답으로 간다.
     *
     * <p>남는 것은 이름표가 없는 자유 저작 표다. 그때는 <b>행 안에서</b> 대조한다:
     * 인자 이름과 같은 컬럼이 있고 그 값이 인자 값과 맞는 행에서만 읽는다. 표 전체를
     * 믿으면 다른 설비의 값을 이 절차의 사실로 들인다.
     */
    private List<String> sameRunValues(String key, ChatDataSnapshot full, String column) {
        return QueryKey.parse(key) != null ? List.of() : matchingRowValues(full, column);
    }

    /** run 인자가 전부 일치하는 행에서만 그 컬럼을 읽는다. 대조할 컬럼이 없으면 빈 목록. */
    private List<String> matchingRowValues(ChatDataSnapshot full, String column) {
        List<String> columns = full.columns();
        if (columns == null) {
            return List.of();
        }
        int target = indexOf(columns, column);
        if (target < 0) {
            return List.of();
        }
        Map<Integer, String> mustMatch = new LinkedHashMap<>();
        for (Map.Entry<String, String> arg : args.entrySet()) {
            int at = indexOf(columns, arg.getKey());
            if (at < 0) {
                return List.of(); // 대조할 수 없는 표 — 같은 대상인지 알 수 없다.
            }
            mustMatch.put(at, arg.getValue());
        }
        if (mustMatch.isEmpty()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (List<String> row : full.rows()) {
            if (row != null && matches(row, mustMatch)
                    && target < row.size() && row.get(target) != null
                    && !row.get(target).isBlank()) {
                values.add(row.get(target).trim());
            }
        }
        return new ArrayList<>(values);
    }

    private static boolean matches(List<String> row, Map<Integer, String> mustMatch) {
        for (Map.Entry<Integer, String> want : mustMatch.entrySet()) {
            int at = want.getKey();
            if (at >= row.size() || row.get(at) == null
                    || !row.get(at).trim().equalsIgnoreCase(want.getValue().trim())) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            String c = columns.get(i);
            if (c != null && name != null && c.trim().equalsIgnoreCase(name.trim())) {
                return i;
            }
        }
        return -1;
    }

    /** 컬럼 목록을 모르면(null) 있다고 본다 — 모름을 부재로 접지 않는다. */
    private static boolean hasColumn(List<String> columns, String column) {
        if (columns == null || column == null) {
            return true;
        }
        return columns.stream()
                .anyMatch(c -> c != null && c.trim().equalsIgnoreCase(column.trim()));
    }

    private String keyOf(String queryId) {
        String full = skill + "#" + queryId;
        return argsPart.isEmpty() ? full : full + "__" + argsPart;
    }
}

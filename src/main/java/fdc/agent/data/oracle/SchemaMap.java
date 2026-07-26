package fdc.agent.data.oracle;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ★★★ 사내에서 채우는 "단 하나의 파일" ★★★
 *
 * 계약(EquipmentDetail / Compare 등)의 각 필드가 사내 Oracle 의 어느
 * 테이블·컬럼인지 매핑한다. 여기 값만 실명으로 바꾸면 OracleEquipmentRepo 의
 * SQL 이 그대로 동작한다 — 코드(SQL 로직)는 손대지 않는다.
 *
 * ⚠️ 보안: table/*Col 값은 SQL 에 **식별자로 직접 삽입**된다(테이블/컬럼명은
 * bind 변수로 못 넘김). 그래서 모든 식별자는 assertIdent 로 화이트리스트
 * 검증한다. 절대 요청 값으로 테이블/컬럼명을 만들지 말 것.
 */
public final class SchemaMap {
    private SchemaMap() {
    }

    public record EquipmentMap(
            String table, String idCol, String nameCol, String modelCol,
            List<String> valueCols, List<String> valueLabels) {
    }

    /** chamber/sensor 공용(설비 1:N). orderByCol 없으면 idCol. */
    public record ChildMap(
            String table, String idCol, String equipmentFkCol,
            List<String> valueCols, List<String> valueLabels, String orderByCol) {
    }

    public record SetupEventMap(
            String table, String equipmentFkCol, String timeCol, String typeCol,
            String labelCol, Map<String, String> typeCodeMap) {
    }

    // 매핑 — **전부 placeholder(TODO_)**. 사내에서 실 테이블/컬럼명(table/*Col/
    // valueCols)과 화면 표시명(valueLabels)으로 치환한다. 외부 레포엔 실 스키마 미포함.
    public static final EquipmentMap EQUIPMENT = new EquipmentMap(
            "TODO_EQP", "TODO_EQP_ID", "TODO_EQP_NAME", "TODO_MODEL_CD",
            List.of("TODO_COL1", "TODO_COL2"),
            List.of("TODO_라벨1", "TODO_라벨2"));

    public static final ChildMap CHAMBER = new ChildMap(
            "TODO_CHAMBER", "TODO_CHAMBER_ID", "TODO_EQP_ID",
            List.of("TODO_CH_COL1"), List.of("TODO_챔버라벨1"), "TODO_CHAMBER_ID");

    public static final ChildMap SENSOR = new ChildMap(
            "TODO_SENSOR", "TODO_SENSOR_ID", "TODO_EQP_ID",
            List.of("TODO_SN_COL1"), List.of("TODO_센서라벨1"), "TODO_SENSOR_ID");

    public static final SetupEventMap SETUP_EVENT = new SetupEventMap(
            "TODO_SETUP_EVENT", "TODO_EQP_ID", "TODO_EVENT_TIME",
            "TODO_EVENT_TYPE", "TODO_EVENT_LABEL", Map.of());

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z][A-Za-z0-9_$#]{0,29}$");

    /**
     * Oracle 식별자 화이트리스트 검증. A-Z a-z 0-9 _ $ # 만, 숫자 시작 불가,
     * 30자 이하(19c 표준 128 이지만 보수적으로). `OWNER.TABLE` 은 파트별 검증.
     */
    public static String assertIdent(String id, String ctx) {
        for (String p : id.split("\\.", -1)) {
            if (!IDENT.matcher(p).matches()) {
                throw new IllegalArgumentException(
                        "unsafe Oracle identifier for " + ctx + ": \"" + id + "\" — "
                                + "SchemaMap 값을 확인하세요 (A-Z0-9_$# 만, 숫자 시작 불가).");
            }
        }
        return id;
    }

    /** 각 섹션의 표시 라벨 (valueLabels 우선, 없으면 valueCols). */
    public static List<String> labelsFor(List<String> valueCols, List<String> valueLabels) {
        return valueLabels != null ? valueLabels : valueCols;
    }
}

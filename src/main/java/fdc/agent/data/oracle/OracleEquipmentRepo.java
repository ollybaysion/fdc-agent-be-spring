package fdc.agent.data.oracle;

import static fdc.agent.data.oracle.SchemaMap.assertIdent;
import static fdc.agent.data.oracle.SchemaMap.labelsFor;

import fdc.agent.config.ApiException;
import fdc.agent.contract.Compare;
import fdc.agent.contract.EquipmentDetail;
import fdc.agent.contract.EquipmentDetail.EquipmentSection;
import fdc.agent.contract.EquipmentDetail.SectionRow;
import fdc.agent.contract.SetupEvent;
import fdc.agent.data.EquipmentRepo;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Oracle 기반 EquipmentRepo(Node 판 oracle/equipmentRepo.ts 대응). SQL 은
 * SchemaMap 의 식별자로 조립하고, 값은 항상 bind 변수(:id 등)로 넘긴다.
 *
 * detail / peers / setup-events 는 완성형(사내엔 SchemaMap 이름만 채우면 됨).
 * compare 는 분석 쿼리라 사내에서 SQL 작성 필요 — 원본 runbook §5 참고.
 */
public class OracleEquipmentRepo implements EquipmentRepo {

    private final JdbcClient jdbc;

    public OracleEquipmentRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private List<Map<String, Object>> query(String sql, Map<String, ?> binds) {
        return jdbc.sql(sql).params(Map.copyOf(binds)).query().listOfRows();
    }

    /** value 컬럼들을 `col AS "V0", col AS "V1"` 로. 각 식별자 검증. */
    private static String valueSelect(List<String> cols, String ctx) {
        return IntStream.range(0, cols.size())
                .mapToObj(i -> assertIdent(cols.get(i), ctx + ".valueCols[" + i + "]") + " AS \"V" + i + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    /** row 에서 V0..V{n-1} 를 순서대로. null → "". */
    private static List<String> readValues(Map<String, Object> row, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> {
                    Object v = row.get("V" + i);
                    return v == null ? "" : String.valueOf(v);
                })
                .toList();
    }

    @Override
    public EquipmentDetail getDetail(String id) {
        SchemaMap.EquipmentMap eq = SchemaMap.EQUIPMENT;
        String table = assertIdent(eq.table(), "equipment.table");
        String idCol = assertIdent(eq.idCol(), "equipment.idCol");
        String nameCol = assertIdent(eq.nameCol(), "equipment.nameCol");
        String modelCol = assertIdent(eq.modelCol(), "equipment.modelCol");

        List<Map<String, Object>> rows = query(
                "SELECT " + idCol + " AS \"ID\", " + nameCol + " AS \"NAME\", " + modelCol + " AS \"MODEL\", "
                        + valueSelect(eq.valueCols(), "equipment")
                        + " FROM " + table
                        + " WHERE " + idCol + " = :id",
                Map.of("id", id));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);

        List<SectionRow> chambers = getChildren(SchemaMap.CHAMBER, id, "chamber");
        List<SectionRow> sensors = getChildren(SchemaMap.SENSOR, id, "sensor");

        return new EquipmentDetail(
                String.valueOf(row.get("ID")),
                String.valueOf(row.get("NAME")),
                String.valueOf(row.get("MODEL")),
                List.of(
                        new EquipmentSection("equipment", "설비 정보",
                                labelsFor(eq.valueCols(), eq.valueLabels()),
                                List.of(new SectionRow(
                                        String.valueOf(row.get("ID")),
                                        readValues(row, eq.valueCols().size())))),
                        new EquipmentSection("chamber", "챔버 정보",
                                labelsFor(SchemaMap.CHAMBER.valueCols(), SchemaMap.CHAMBER.valueLabels()),
                                chambers),
                        new EquipmentSection("sensor", "센서 정보",
                                labelsFor(SchemaMap.SENSOR.valueCols(), SchemaMap.SENSOR.valueLabels()),
                                sensors)));
    }

    /** chamber/sensor 공통 조회 (설비 FK = :id). */
    private List<SectionRow> getChildren(SchemaMap.ChildMap m, String equipmentId, String ctx) {
        String table = assertIdent(m.table(), ctx + ".table");
        String idCol = assertIdent(m.idCol(), ctx + ".idCol");
        String fkCol = assertIdent(m.equipmentFkCol(), ctx + ".equipmentFkCol");
        String orderBy = assertIdent(m.orderByCol() != null ? m.orderByCol() : m.idCol(), ctx + ".orderByCol");

        List<Map<String, Object>> rows = query(
                "SELECT " + idCol + " AS \"ID\", " + valueSelect(m.valueCols(), ctx)
                        + " FROM " + table
                        + " WHERE " + fkCol + " = :id"
                        + " ORDER BY " + orderBy,
                Map.of("id", equipmentId));
        return rows.stream()
                .map(r -> new SectionRow(String.valueOf(r.get("ID")), readValues(r, m.valueCols().size())))
                .toList();
    }

    @Override
    public List<EquipmentDetail> getPeers(String id) {
        SchemaMap.EquipmentMap eq = SchemaMap.EQUIPMENT;
        String table = assertIdent(eq.table(), "equipment.table");
        String idCol = assertIdent(eq.idCol(), "equipment.idCol");
        String modelCol = assertIdent(eq.modelCol(), "equipment.modelCol");

        List<Map<String, Object>> peerIdRows = query(
                "SELECT p." + idCol + " AS \"ID\""
                        + " FROM " + table + " p"
                        + " JOIN " + table + " me ON p." + modelCol + " = me." + modelCol
                        + " WHERE me." + idCol + " = :id"
                        + " AND p." + idCol + " <> :id"
                        + " ORDER BY p." + idCol,
                Map.of("id", id));
        return peerIdRows.stream()
                .map(r -> getDetail(String.valueOf(r.get("ID"))))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<SetupEvent> getSetupEvents(String id) {
        SchemaMap.SetupEventMap se = SchemaMap.SETUP_EVENT;
        String table = assertIdent(se.table(), "setupEvent.table");
        String fkCol = assertIdent(se.equipmentFkCol(), "setupEvent.equipmentFkCol");
        String timeCol = assertIdent(se.timeCol(), "setupEvent.timeCol");
        String typeCol = assertIdent(se.typeCol(), "setupEvent.typeCol");
        String labelSel = se.labelCol() != null
                ? ", " + assertIdent(se.labelCol(), "setupEvent.labelCol") + " AS \"LABEL\""
                : "";

        List<Map<String, Object>> rows = query(
                "SELECT TO_CHAR(" + timeCol + ", 'YYYY-MM-DD\"T\"HH24:MI') AS \"TIME\", "
                        + typeCol + " AS \"TYPE\"" + labelSel
                        + " FROM " + table
                        + " WHERE " + fkCol + " = :id"
                        + " ORDER BY " + timeCol + " DESC",
                Map.of("id", id));

        return rows.stream().map(r -> {
            String raw = r.get("TYPE") == null ? "" : String.valueOf(r.get("TYPE"));
            String type = se.typeCodeMap().getOrDefault(raw, "other");
            Object label = r.get("LABEL");
            return new SetupEvent(
                    String.valueOf(r.get("TIME")),
                    type,
                    label == null || String.valueOf(label).isEmpty() ? null : String.valueOf(label));
        }).toList();
    }

    @Override
    public Compare.CompareResponse getCompare(String id, String peerId, String recipe, int windowDays) {
        // compare 는 통계·시계열·챔버이벤트·알람을 조합하는 분석 쿼리라 사내에서
        // 실 센서/알람 테이블 기준으로 작성해야 한다(원본 runbook §5).
        throw new ApiException(501, "error",
                "compare Oracle SQL 미구현 — docs/phase1-사내-runbook.md §5 에서 작성 필요");
    }
}

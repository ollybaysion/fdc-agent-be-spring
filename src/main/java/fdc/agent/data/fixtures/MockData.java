package fdc.agent.data.fixtures;

import static fdc.agent.util.Js.hash;

import fdc.agent.contract.EquipmentDetail.SectionRow;
import fdc.agent.contract.SetupEvent;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * demo-fe `src/demo/equipment.ts` 의 결정론 mock 을 이식한 것(Node 판
 * fixtures/mockData.ts 와 동일 로직). 해시/반올림/시각 생성이 JS 와
 * bit-호환이어야 BACKEND_URL 스왑 시 프론트가 차이를 못 느낀다.
 */
public final class MockData {
    private MockData() {
    }

    public record MockEquipmentDetail(
            String id, String name, String model, List<String> values,
            List<SectionRow> chambers, List<SectionRow> sensors) {
    }

    private static final int COL_COUNT = 10;

    private static List<String> mkRow(String prefix) {
        return IntStream.rangeClosed(1, COL_COUNT).mapToObj(i -> prefix + "-v" + i).toList();
    }

    private static MockEquipmentDetail eq(String id, String model, String[] chambers, String[] sensors) {
        return new MockEquipmentDetail(
                id, id, model, mkRow(id),
                Stream.of(chambers).map(c -> new SectionRow(c, mkRow(c))).toList(),
                Stream.of(sensors).map(s -> new SectionRow(s, mkRow(s))).toList());
    }

    public static final List<MockEquipmentDetail> EQUIPMENT_DETAILS = List.of(
            eq("ETCH-01", "EtcherX-2000",
                    new String[] { "ETCH-01-A", "ETCH-01-B" },
                    new String[] { "ETCH-01-APC", "ETCH-01-RFF" }),
            eq("ETCH-02", "EtcherX-2000",
                    new String[] { "ETCH-02-A", "ETCH-02-B" },
                    new String[] { "ETCH-02-APC", "ETCH-02-RFF" }),
            eq("ETCH-03", "EtcherX-2000",
                    new String[] { "ETCH-03-A", "ETCH-03-B", "ETCH-03-C" },
                    new String[] { "ETCH-03-TC1", "ETCH-03-APC" }),
            eq("CVD-01", "VaporPro-1000",
                    new String[] { "CVD-01-A", "CVD-01-B" },
                    new String[] { "CVD-01-T", "CVD-01-G" }),
            eq("CVD-02", "VaporPro-1000",
                    new String[] { "CVD-02-A", "CVD-02-B" },
                    new String[] { "CVD-02-T", "CVD-02-G" }),
            eq("CVD-03", "VaporPro-1000",
                    new String[] { "CVD-03-A", "CVD-03-B" },
                    new String[] { "CVD-03-T", "CVD-03-G" }));

    public static Optional<MockEquipmentDetail> getEquipmentDetail(String idOrName) {
        String trimmed = idOrName == null ? "" : idOrName.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        return EQUIPMENT_DETAILS.stream()
                .filter(e -> e.name().equals(trimmed) || e.id().equals(trimmed))
                .findFirst();
    }

    public static List<MockEquipmentDetail> getPeers(String idOrName) {
        return getEquipmentDetail(idOrName)
                .map(me -> EQUIPMENT_DETAILS.stream()
                        .filter(e -> e.model().equals(me.model()) && !e.id().equals(me.id()))
                        .toList())
                .orElse(List.of());
    }

    // ─────────────────────────────────────────────────────────
    // 셋업 이벤트 — deterministic mock
    // ─────────────────────────────────────────────────────────

    private static String pad2(long n) {
        return String.format("%02d", n);
    }

    private static String setupTimeFor(String equipmentId) {
        long day = 10 + hash("setup|" + equipmentId) % 18;
        long hh = hash("h|" + equipmentId) % 14 + 7;
        long mm = (hash("m|" + equipmentId) % 4) * 15;
        return "2026-04-" + pad2(day) + "T" + pad2(hh) + ":" + pad2(mm);
    }

    public static List<SetupEvent> getSetupEvents(String equipmentId) {
        return List.of(new SetupEvent(setupTimeFor(equipmentId), "setup", "최근 셋업/설비 변경"));
    }
}

package fdc.agent.data.fixtures;

import static fdc.agent.util.Js.hash;
import static fdc.agent.util.Js.num;

import fdc.agent.contract.Compare;
import fdc.agent.contract.EquipmentDetail.SectionRow;
import fdc.agent.contract.SetupEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * demo-fe `src/demo/equipment.ts` 의 결정론 mock 을 이식한 것. 해시/반올림/시각
 * 생성이 원본과 값 단위로 일치해야 BACKEND_URL 스왑 시 프론트가 차이를 못
 * 느낀다.
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
    // 셋업 이벤트 + 비교(post-setup) — deterministic mock
    // ─────────────────────────────────────────────────────────

    private record SensorBase(String name, double mean, double stddev, double max, double min) {
    }

    private static final List<SensorBase> COMPARE_SENSORS = List.of(
            new SensorBase("APC_PRESSURE", 0.95, 0.04, 1.05, 0.85),
            new SensorBase("RF_FORWARD", 1720, 25, 1780, 1650),
            new SensorBase("TEMP_TC1", 243, 1.2, 247, 240),
            new SensorBase("GAS_FLOW_SiH4", 200, 3, 210, 190));

    private static double offsetFor(String equipmentId, String recipe, String sensor) {
        long r = hash(equipmentId + "|" + recipe + "|" + sensor) % 100;
        return (r - 50) / 400.0;
    }

    /** mockData.ts round(): 자릿수별 반올림. JS Math.round 와 동일(half toward +∞). */
    private static Number round(double v) {
        if (v < 10) {
            return num(Math.round(v * 1000) / 1000.0);
        }
        if (v < 100) {
            return num(Math.round(v * 10) / 10.0);
        }
        return Math.round(v);
    }

    private static String pad2(long n) {
        return String.format("%02d", n);
    }

    private static String isoLocalMinute(Instant d) {
        LocalDateTime utc = LocalDateTime.ofInstant(d, ZoneOffset.UTC);
        return String.format("%d-%s-%sT%s:%s",
                utc.getYear(), pad2(utc.getMonthValue()), pad2(utc.getDayOfMonth()),
                pad2(utc.getHour()), pad2(utc.getMinute()));
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

    private static List<Compare.SensorStats> statsFor(String equipmentId, String recipe) {
        return COMPARE_SENSORS.stream().map(s -> {
            double o = offsetFor(equipmentId, recipe, s.name());
            Number mean = round(s.mean() * (1 + o));
            Number stddev = round(s.stddev() * (1 + Math.abs(o) * 1.4));
            Number max = round(s.max() * (1 + Math.max(o, 0) * 1.2));
            Number min = round(s.min() * (1 + Math.min(o, 0) * 1.2));
            long anomalies = Math.max(0, Math.round(Math.abs(o) * 12));
            return new Compare.SensorStats(s.name(), mean, stddev, max, min, anomalies);
        }).toList();
    }

    private static Compare.MatchedRun matchedRunFor(String equipmentId, String recipe, int windowDays) {
        boolean skip = hash("skip|" + equipmentId + recipe) % 7 == 0;
        if (skip && windowDays == 1) {
            return null;
        }
        long setupTs = LocalDateTime.parse(setupTimeFor(equipmentId))
                .toInstant(ZoneOffset.UTC).toEpochMilli();
        long offsetH = hash("run|" + equipmentId + recipe) % (windowDays * 24L - 4) + 2;
        Instant start = Instant.ofEpochMilli(setupTs + offsetH * 3_600_000L);
        LocalDateTime utc = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
        String id = "RUN-" + equipmentId + "-" + recipe + "-"
                + pad2(utc.getDayOfMonth()) + pad2(utc.getHour());
        long durationMin = 35 + hash("dur|" + equipmentId + recipe) % 25;
        return new Compare.MatchedRun(id, isoLocalMinute(start), durationMin);
    }

    private static List<Compare.SensorSeries> seriesFor(
            String currentId, String baselineId, String recipe, long durationMin) {
        long points = Math.min(durationMin + 1, 90);
        return COMPARE_SENSORS.stream().map(s -> {
            double oc = offsetFor(currentId, recipe, s.name());
            double ob = offsetFor(baselineId, recipe, s.name());
            List<Map<String, Number>> data = new ArrayList<>();
            for (long t = 0; t < points; t++) {
                double phase = (double) t / points;
                double shape = phase < 0.15 ? phase / 0.15 : phase > 0.85 ? (1 - phase) / 0.15 : 1;
                double wobbleC = Math.sin((t + hash(currentId + s.name())) / 4.0) * 0.02;
                double wobbleB = Math.sin((t + hash(baselineId + s.name())) / 4.0) * 0.02;
                double valueC = s.mean() * shape * (1 + oc + wobbleC);
                double valueB = s.mean() * shape * (1 + ob + wobbleB);
                Map<String, Number> point = new LinkedHashMap<>();
                point.put("t", t);
                point.put(currentId, round(valueC));
                point.put(baselineId, round(valueB));
                data.add(point);
            }
            return new Compare.SensorSeries(s.name(), data);
        }).toList();
    }

    private static List<Compare.ChamberEvent> chamberEventsFor(
            String equipmentId, String recipe, long durationMin) {
        long seed = hash(equipmentId + recipe);
        long dur = Math.max(durationMin, 20);
        List<Compare.ChamberEvent> out = new ArrayList<>();
        out.add(new Compare.ChamberEvent(0L, 2 + seed % 3, "setup", "Setup 안정화"));
        long recipeT = (long) Math.floor(dur * 0.3) + seed % 4;
        out.add(new Compare.ChamberEvent(
                recipeT, null, "recipe_change", recipe + " → step " + (1 + (seed >> 1) % 3)));
        long clStart = (long) Math.floor(dur * 0.55) + (seed >> 2) % 4;
        out.add(new Compare.ChamberEvent(
                clStart, clStart + 4 + (seed >> 3) % 3, "cleaning", "Inter-step purge"));
        if (seed % 5 < 2) {
            long mT = (long) Math.floor(dur * 0.75) + (seed >> 4) % 3;
            out.add(new Compare.ChamberEvent(mT, null, "maintenance", "Quick PM check"));
        }
        out.sort(Comparator.comparingLong(e -> e.start().longValue()));
        return out;
    }

    private record AlarmDef(String code, String label, String severity, String sensor) {
    }

    private static final List<AlarmDef> ALARM_POOL = List.of(
            new AlarmDef("RF_HIGH", "RF_FORWARD 임계 초과", "critical", "RF_FORWARD"),
            new AlarmDef("GAS_LEAK_A", "Chamber A GasLeak", "critical", "GAS_FLOW_SiH4"),
            new AlarmDef("TEMP_RISE", "TEMP 상승 트렌드", "warning", "TEMP_TC1"),
            new AlarmDef("APC_DRIFT", "APC 압력 드리프트", "warning", "APC_PRESSURE"),
            new AlarmDef("RF_REFL", "RF_REFLECTED 비정상", "warning", "RF_FORWARD"),
            new AlarmDef("MFC_VAR", "MFC 응답 변동", "info", "GAS_FLOW_SiH4"));

    private static List<Compare.AlarmEvent> alarmsFor(
            String equipmentId, String recipe, long durationMin) {
        long seed = hash(equipmentId + recipe + "alarm");
        long dur = Math.max(durationMin, 10);
        long count = 2 + seed % 4;
        List<Compare.AlarmEvent> out = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            AlarmDef def = ALARM_POOL.get((int) ((seed + i * 17) % ALARM_POOL.size()));
            long t = (long) Math.floor((double) (dur * (i + 1)) / (count + 1)) + (seed >> i) % 3 - 1;
            long time = Math.max(0, Math.min(dur - 1, t));
            Compare.RootCause rootCause = new Compare.RootCause(
                    def.sensor(),
                    ((seed >> (i + 1)) & 1) == 0 ? "A" : "B",
                    def.sensor() + " > threshold for 10s",
                    round(50 + (seed + i * 7) % 100));
            out.add(new Compare.AlarmEvent(time, null, def.code(), def.label(), def.severity(), rootCause));
        }
        out.sort(Comparator.comparingLong(e -> e.time().longValue()));
        return out;
    }

    public static Compare.CompareResponse getCompareData(
            String currentId, String baselineId, String recipe, int windowDays) {
        Compare.MatchedRun currentRun = matchedRunFor(currentId, recipe, windowDays);
        Compare.MatchedRun baselineRun = matchedRunFor(baselineId, recipe, windowDays);
        long dur = Math.min(
                currentRun != null ? currentRun.durationMin().longValue() : 0,
                baselineRun != null ? baselineRun.durationMin().longValue() : 0);
        boolean both = currentRun != null && baselineRun != null;
        return new Compare.CompareResponse(
                recipe,
                windowDays,
                new Compare.CompareSide(currentId, setupTimeFor(currentId), currentRun, statsFor(currentId, recipe)),
                new Compare.CompareSide(baselineId, setupTimeFor(baselineId), baselineRun, statsFor(baselineId, recipe)),
                both ? seriesFor(currentId, baselineId, recipe, dur) : List.of(),
                both
                        ? new Compare.Sides<>(
                                chamberEventsFor(currentId, recipe, dur),
                                chamberEventsFor(baselineId, recipe, dur))
                        : new Compare.Sides<>(List.of(), List.of()),
                both
                        ? new Compare.Sides<>(
                                alarmsFor(currentId, recipe, dur),
                                alarmsFor(baselineId, recipe, dur))
                        : new Compare.Sides<>(List.of(), List.of()));
    }
}

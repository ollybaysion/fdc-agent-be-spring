package fdc.agent.skills;

import static fdc.agent.util.Js.hash;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.AgentTool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 스킬 레지스트리(Node 판 skills/registry.ts 대응) — classpath `skills/` 의
 * 모든 `<name>.spec.json` 을 자동 스캔해 에이전트 툴로 컴파일한다. 새 스킬
 * 추가 = 그 폴더에 spec.json + wiring.json 두 파일을 떨구면 끝(코드 편집 0).
 *
 * SQL 조회 함수는 seam: oracle 모드는 실 Oracle(JdbcClient), fixture 모드는
 * 아래 seed(골든 spec 의 예시 데이터)로 동작해 사내 없이도 데모/검증 가능.
 */
public final class SkillRegistry {
    private SkillRegistry() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private record LoadedSkill(SkillSpec spec, SkillWiring wiring) {
    }

    // 모듈 로드 시 1회 스캔(파일명 정렬해 결정적 순서).
    private static final List<LoadedSkill> LOADED = loadAllSkills();

    private static List<LoadedSkill> loadAllSkills() {
        try {
            Resource[] specs = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:skills/*.spec.json");
            return Arrays.stream(specs)
                    .sorted(Comparator.comparing(Resource::getFilename))
                    .map(specRes -> {
                        String base = specRes.getFilename().replaceAll("\\.spec\\.json$", "");
                        return new LoadedSkill(
                                readJson(specRes, SkillSpec.class),
                                readJson("skills/" + base + ".wiring.json", SkillWiring.class));
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("스킬 폴더 스캔 실패", e);
        }
    }

    private static <T> T readJson(Resource res, Class<T> type) {
        try (var in = res.getInputStream()) {
            return JSON.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "스킬 파일 로드 실패: " + res.getFilename() + " — " + e.getMessage(), e);
        }
    }

    private static <T> T readJson(String classpath, Class<T> type) {
        return readJson(new PathMatchingResourcePatternResolver().getResource("classpath:" + classpath), type);
    }

    /** 도메인 스킬 툴 목록 — 폴더의 모든 spec 을 컴파일. */
    public static List<AgentTool> buildSkillTools(SkillQuery skillQuery) {
        return LOADED.stream()
                .map(s -> SkillLoader.loadSkill(s.spec(), skillQuery, s.wiring()))
                .toList();
    }

    // ── fixture 모드 seed (골든 spec 의 예시: S-0004 = CVD-01 FLOW, 비활성) ──
    // Oracle OUT_FORMAT_OBJECT 처럼 컬럼/별칭을 대문자 키로.
    private static final List<Map<String, Object>> SEED_SENSORS = List.of(
            seedRow("SNSR_ID", "S-0004", "EQP_ID", "CVD-01", "SNSR_TYPE_CD", "FLOW", "UNIT_CD", "SCCM", "USE_YN", "N"),
            seedRow("SNSR_ID", "S-0001", "EQP_ID", "CVD-01", "SNSR_TYPE_CD", "TEMP", "UNIT_CD", "C", "USE_YN", "Y"),
            seedRow("SNSR_ID", "S-0007", "EQP_ID", "ETCH-01", "SNSR_TYPE_CD", "PRESSURE", "UNIT_CD", "mTorr", "USE_YN", "Y"));

    private static final List<Map<String, Object>> SEED_EQUIPMENT = List.of(
            seedRow("EQP_ID", "CVD-01", "EQP_NAME", "증착기 1호", "MODEL_CD", "CV-800", "VENDOR", "AMAT", "USE_YN", "N"),
            seedRow("EQP_ID", "ETCH-01", "EQP_NAME", "식각기 1호", "MODEL_CD", "EtcherX-2000", "VENDOR", "LAM", "USE_YN", "Y"));

    // EQP_ID 는 필터용, SELECT 결과(D/EVT_TYPE_CD/EVT_LABEL)만 반환.
    private static final List<Map<String, Object>> SEED_EVENTS = List.of(
            seedRow("EQP_ID", "CVD-01", "D", "2026-05-11", "EVT_TYPE_CD", "O", "EVT_LABEL", "라인 점검"),
            seedRow("EQP_ID", "CVD-01", "D", "2026-04-20", "EVT_TYPE_CD", "M", "EVT_LABEL", "정기 PM"),
            seedRow("EQP_ID", "ETCH-01", "D", "2026-05-01", "EVT_TYPE_CD", "S", "EVT_LABEL", "레시피 셋업"));

    private static Map<String, Object> seedRow(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    /** fixture 모드 SkillQuery — seed 를 대상 테이블·bind 로 필터해 반환. */
    public static final SkillQuery FIXTURE_SKILL_QUERY = (sql, binds) -> {
        String s = sql.toLowerCase();
        // ⚠ fdc_sensor_reading 은 fdc_sensor 를 포함하므로 먼저 검사.
        if (s.contains("fdc_sensor_reading")) {
            return List.of(readingStats(
                    String.valueOf(binds.getOrDefault("eqp", "")),
                    String.valueOf(binds.getOrDefault("pidx", ""))));
        }
        if (s.contains("fdc_sensor")) {
            return SEED_SENSORS.stream()
                    .filter(r -> r.get("SNSR_ID").equals(binds.get("id")))
                    .toList();
        }
        if (s.contains("fdc_equipment")) {
            return SEED_EQUIPMENT.stream()
                    .filter(r -> r.get("EQP_ID").equals(binds.get("eqp")))
                    .toList();
        }
        if (s.contains("fdc_setup_event")) {
            return SEED_EVENTS.stream()
                    .filter(r -> r.get("EQP_ID").equals(binds.get("eqp")))
                    .limit(3)
                    .map(r -> {
                        Map<String, Object> rest = new LinkedHashMap<>(r);
                        rest.remove("EQP_ID");
                        return rest;
                    })
                    .map(m -> (Map<String, Object>) m)
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }
        return List.of();
    };

    /** (설비, 센서) 조합별 결정적 측정 통계 — 데모용(analyze 스킬 집계 결과 모사). */
    private static Map<String, Object> readingStats(String eqp, String snsr) {
        long h = hash(eqp + "|" + snsr);
        double base = 50 + h % 150;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("CNT", 300 + h % 700);
        row.put("MEAN", round2(base));
        row.put("SD", round2(base * 0.03));
        row.put("MINV", round2(base * 0.9));
        row.put("MAXV", round2(base * 1.12));
        row.put("ANOM", h % 6);
        return row;
    }

    private static Number round2(double v) {
        return fdc.agent.util.Js.num(Math.round(v * 100) / 100.0);
    }
}

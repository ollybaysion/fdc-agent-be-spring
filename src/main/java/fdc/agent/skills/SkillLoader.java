package fdc.agent.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.AgentTool;
import fdc.agent.chat.AgentTool.ToolResult;
import fdc.agent.contract.ChatTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * skill-loader — forge-domain-skill 의 spec.json 을 에이전트 툴로 컴파일
 * (Node 판 skills/skill-loader.ts 대응). 하이브리드(C) 모델: 조회(steps[].sql)
 * 는 코드가 순서·bind 로 결정론 실행, notes/valueRules/output.template 는
 * 번역 없이 프로즈 그대로 결과에 실어 LLM 이 자연어 서술에 참고하게 한다.
 * LLM 은 SQL 텍스트를 보지 않는다.
 */
public final class SkillLoader {
    private SkillLoader() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static AgentTool loadSkill(SkillSpec spec, SkillQuery query, SkillWiring wiring) {
        String toolName = spec.name().replace("-", "_");
        Map<String, Object> properties = new LinkedHashMap<>();
        for (SkillWiring.SkillArg a : wiring.args()) {
            properties.put(a.name(), Map.of(
                    "type", "string",
                    "description", a.description() != null ? a.description() : spec.argumentHint()));
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", wiring.args().stream().map(SkillWiring.SkillArg::name).toList());

        return new AgentTool(
                toolName,
                firstLine(spec.description()),
                parameters,
                args -> runSkill(spec, query, wiring, args));
    }

    private static ToolResult runSkill(
            SkillSpec spec, SkillQuery query, SkillWiring wiring, Map<String, Object> args) {
        List<List<Map<String, Object>>> stepRows = new ArrayList<>();

        for (int i = 0; i < spec.steps().size(); i++) {
            Map<String, SkillWiring.BindSource> bindSpec =
                    wiring.binds() != null ? wiring.binds().getOrDefault(i, Map.of()) : Map.of();
            Map<String, Object> binds = new LinkedHashMap<>();
            boolean missingBind = false;

            for (Map.Entry<String, SkillWiring.BindSource> e : bindSpec.entrySet()) {
                SkillWiring.BindSource src = e.getValue();
                if ("arg".equals(src.from())) {
                    // 인자 이름으로 조회 — 다중 인자 지원.
                    Object raw = args.get(src.arg());
                    String v = raw == null ? "" : String.valueOf(raw).trim();
                    if (v.isEmpty()) {
                        missingBind = true;
                        break;
                    }
                    binds.put(e.getKey(), v);
                } else {
                    List<Map<String, Object>> prevRows =
                            src.step() != null && src.step() < stepRows.size() ? stepRows.get(src.step()) : null;
                    Object value = prevRows != null && !prevRows.isEmpty()
                            ? prevRows.get(0).get(src.column())
                            : null;
                    if (value == null) {
                        // 바인드 소스가 없음(직전 스텝 0행 등) → 이 스텝 스킵.
                        missingBind = true;
                        break;
                    }
                    binds.put(e.getKey(), value);
                }
            }

            if (missingBind) {
                stepRows.add(List.of());
                continue;
            }
            stepRows.add(query.query(spec.steps().get(i).sql(), binds));
        }

        return buildResult(spec, stepRows);
    }

    /** 조회 사실 + 해석 규칙 + 출력 형식(프로즈)을 LLM 서술용으로 조립 + 표. */
    private static ToolResult buildResult(SkillSpec spec, List<List<Map<String, Object>>> stepRows) {
        List<String> parts = new ArrayList<>();
        parts.add("[조회 결과]");
        for (int i = 0; i < spec.steps().size(); i++) {
            List<Map<String, Object>> rows = i < stepRows.size() ? stepRows.get(i) : List.of();
            parts.add("- " + spec.steps().get(i).title() + ": "
                    + (rows.isEmpty() ? "(0행)" : stringify(rows)));
        }

        if (spec.valueRules() != null && !spec.valueRules().isEmpty()) {
            parts.add("[값 해석 규칙]");
            for (SkillSpec.SkillValueRule r : spec.valueRules()) {
                parts.add("- " + r.target() + ": " + r.rule());
            }
        }
        if (spec.output() != null && spec.output().template() != null) {
            parts.add("[출력 형식 — 이 형식대로 자연어 한 문단으로 답하라]");
            parts.add(spec.output().template());
        }

        List<ChatTable> tables = new ArrayList<>();
        for (int i = 0; i < spec.steps().size(); i++) {
            List<Map<String, Object>> rows = i < stepRows.size() ? stepRows.get(i) : List.of();
            if (!rows.isEmpty()) {
                tables.add(rowsToTable(spec.steps().get(i).title(), rows));
            }
        }

        return new ToolResult(String.join("\n", parts), tables.isEmpty() ? null : tables);
    }

    private static ChatTable rowsToTable(String title, List<Map<String, Object>> rows) {
        List<String> columns = rows.isEmpty() ? List.of() : List.copyOf(rows.get(0).keySet());
        return new ChatTable(title, columns, rows);
    }

    private static String stringify(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String firstLine(String s) {
        return s.split("\n", -1)[0].trim();
    }
}

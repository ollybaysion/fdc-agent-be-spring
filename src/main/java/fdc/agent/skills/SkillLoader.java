package fdc.agent.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.AgentTool;
import fdc.agent.chat.AgentTool.ToolResult;
import fdc.agent.contract.ChatTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * skill-loader — forge-domain-skill 의 spec.json(v2) 을 에이전트 툴로 컴파일.
 * 하이브리드(C) 모델: 조회(steps[].sql)는 코드가 순서·bind 로 결정론 실행,
 * 출력 지침(서술 지시·반드시 포함·avoid·examples)은 번역 없이 프로즈 그대로
 * 결과에 실어 LLM 이 자연어 서술에 참고하게 한다. LLM 은 SQL 을 보지 않는다.
 *
 * <p>{@code description} 은 spec 필드가 아니라 여기서 합성된다 — 합성 결과를
 * 공유 픽스처로 묶지 않고 소비자가 각자 만들기로 한 결정이라(foundry 설계
 * §4-1), 아래 골격은 foundry {@code render-skill.mjs} 의 것을 그대로 옮긴
 * 것이다. 골격이 바뀌면 같이 고칠 것.
 */
public final class SkillLoader {
    private SkillLoader() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static AgentTool loadSkill(SkillSpec spec, SkillQuery query) {
        validateBinds(spec);
        String toolName = spec.name().replace("-", "_");
        Map<String, Object> properties = new LinkedHashMap<>();
        for (SkillSpec.SkillInput p : spec.inputs()) {
            properties.put(p.name(), Map.of("type", "string", "description", p.description()));
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", spec.inputs().stream()
                .filter(SkillSpec.SkillInput::required)
                .map(SkillSpec.SkillInput::name)
                .toList());

        // 스킬은 guidance 를 내지 않는다 — 합성된 description 이 이미 "언제 부르나"다.
        return AgentTool.of(
                toolName,
                synthesizeDescription(spec),
                parameters,
                args -> runSkill(spec, query, args));
    }

    private static final Pattern BIND_VAR = Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)");

    /**
     * 로드 시 배선 검증 — akg envelope.mjs 시맨틱 체크와 같은 3종(인자 실재 ·
     * 앞 스텝만 · SQL {@code :var} 집합과 정확 일치). 실행기의 책임은 배선의
     * 저작이 아니라 나쁜 선언의 거부다: 어긋난 spec 은 런타임 침묵 스킵이
     * 아니라 기동 실패로 드러난다(이슈 #7 ②의 이름-매칭 사고 방지).
     *
     * <p>public 인 이유: {@link AkgSkillSource} 가 허브에서 받은 spec 을
     * 수용 전에 같은 검증으로 거른다 — 나쁜 문서 하나가 챗을 못 죽이게.
     */
    public static void validateBinds(SkillSpec spec) {
        Set<String> inputNames = new HashSet<>();
        for (SkillSpec.SkillInput p : spec.inputs()) {
            inputNames.add(p.name());
        }
        for (int i = 0; i < spec.steps().size(); i++) {
            SkillSpec.SkillStep step = spec.steps().get(i);
            Map<String, SkillSpec.BindSource> binds = step.binds() != null ? step.binds() : Map.of();
            Set<String> sqlVars = extractBindVars(step.sql());
            for (Map.Entry<String, SkillSpec.BindSource> e : binds.entrySet()) {
                SkillSpec.BindSource src = e.getValue();
                String at = spec.name() + " steps[" + i + "].binds." + e.getKey();
                if ("arg".equals(src.from())) {
                    if (!inputNames.contains(src.arg())) {
                        throw new IllegalStateException(at + ": no input named \"" + src.arg() + "\"");
                    }
                } else if ("step".equals(src.from())) {
                    if (src.step() == null || src.step() < 0 || src.step() >= i) {
                        throw new IllegalStateException(at + ": step " + src.step() + " is not an earlier step");
                    }
                } else {
                    throw new IllegalStateException(at + ": unknown from \"" + src.from() + "\"");
                }
                if (!sqlVars.contains(e.getKey())) {
                    throw new IllegalStateException(at + ": declared but sql has no :" + e.getKey());
                }
            }
            for (String v : sqlVars) {
                if (!binds.containsKey(v)) {
                    throw new IllegalStateException(spec.name() + " steps[" + i + "]: sql uses :" + v
                            + " but binds does not declare it");
                }
            }
        }
    }

    /** SQL 의 {@code :bind} 변수 추출 — 따옴표 리터럴은 벗긴다(날짜 마스크 ':' 오탐 방지). */
    private static Set<String> extractBindVars(String sql) {
        Set<String> vars = new HashSet<>();
        Matcher m = BIND_VAR.matcher(sql.replaceAll("'[^']*'", "''"));
        while (m.find()) {
            vars.add(m.group(1));
        }
        return vars;
    }

    /** 한국어 조사 일치 — foundry 렌더러와 같은 규칙(합성 결과가 갈리지 않도록). */
    private static boolean hasFinalConsonant(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return false;
        }
        char last = t.charAt(t.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return false;
        }
        return (last - 0xAC00) % 28 != 0;
    }

    /**
     * 라우팅 문장 합성 — "언제 부르나"(트리거)만 적는다. 골격은 {@code scope.의도}
     * 가 고르고, 도메인 어휘는 {@code focus} 한 구절뿐이다.
     */
    public static String synthesizeDescription(SkillSpec spec) {
        String when = spec.inputs().stream()
                .filter(SkillSpec.SkillInput::required)
                .map(SkillSpec.SkillInput::name)
                .collect(java.util.stream.Collectors.joining("·")) + " 필요";
        if ("생성 이력".equals(spec.scope().의도())) {
            String particle = hasFinalConsonant(spec.focus()) ? "이" : "가";
            return "특정 " + spec.focus() + particle + " 어떻게 만들어졌는지 묻는 상황에서 호출한다 (" + when + ").";
        }
        String particle = hasFinalConsonant(spec.focus()) ? "을" : "를";
        return "특정 " + spec.scope().단위() + "의 " + spec.focus() + particle
                + " 묻는 상황에서 호출한다 (" + when + ").";
    }

    private static ToolResult runSkill(SkillSpec spec, SkillQuery query, Map<String, Object> args) {
        List<List<Map<String, Object>>> stepRows = new ArrayList<>();

        for (int i = 0; i < spec.steps().size(); i++) {
            SkillSpec.SkillStep step = spec.steps().get(i);
            Map<String, SkillSpec.BindSource> bindSpec = step.binds() != null ? step.binds() : Map.of();
            Map<String, Object> binds = new LinkedHashMap<>();
            boolean missingBind = false;

            for (Map.Entry<String, SkillSpec.BindSource> e : bindSpec.entrySet()) {
                SkillSpec.BindSource src = e.getValue();
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
            stepRows.add(query.query(step.sql(), binds));
        }

        return buildResult(spec, stepRows);
    }

    /**
     * 조회 사실 + 출력 지침(프로즈)을 LLM 서술용으로 조립 + 표.
     *
     * <p>v2: 형식은 강제하지 않고 <b>내용에만 바닥</b>을 둔다 — 서술 지시 +
     * 반드시 포함({@code steps[].produces}) + 하지 말 것 + 예시. 값 의미(코드표)는
     * 스킬에 없다: 표준 db-schema 문서 fetch 경로는 후속 과제(foundry 설계 §13).
     */
    private static ToolResult buildResult(SkillSpec spec, List<List<Map<String, Object>>> stepRows) {
        List<String> parts = new ArrayList<>();
        parts.add("[조회 결과]");
        for (int i = 0; i < spec.steps().size(); i++) {
            List<Map<String, Object>> rows = i < stepRows.size() ? stepRows.get(i) : List.of();
            parts.add("- " + spec.steps().get(i).title() + ": "
                    + (rows.isEmpty() ? "(0행)" : stringify(rows)));
        }

        String particle = hasFinalConsonant(spec.focus()) ? "을" : "를";
        parts.add("[출력 지침]");
        parts.add("조회한 데이터로 " + spec.scope().단위() + "의 " + spec.focus() + particle
                + " 설명한다. 정해진 형식은 없다.");
        parts.add("체계적·논리적으로, 없는 정보는 지어내지 않는다.");

        List<String> produces = spec.steps().stream()
                .map(SkillSpec.SkillStep::produces)
                .filter(p -> p != null && !p.isBlank())
                .toList();
        if (!produces.isEmpty()) {
            parts.add("반드시 포함 (질문이 특정 항목만 묻는 게 아니면): " + String.join(" · ", produces));
        }

        parts.add("[하지 말 것]");
        for (String a : spec.output().avoid()) {
            parts.add("- " + a);
        }

        parts.add("[예시 — 모양만 참고, 값은 조회 결과로 바꾼다]");
        for (SkillSpec.SkillExample ex : spec.output().examples()) {
            parts.add("질문: " + ex.ask());
            parts.add("답: " + ex.answer());
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
}

package fdc.agent.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 조달 요청의 안정 키 — <b>BE 가 만든다</b>. 모델이 짓던 문자열이 사라져 억제가 정확
 * 일치로도 흔들리지 않고, 무엇보다 <b>키 자체가 진행 상태를 들고 있다</b>.
 *
 * <pre>
 * fdc-explain-sensor#0__snsr_id=S-0004
 * └─ 스킬 ─────────┘ └단계┘ └─ run 이름표 ─┘
 * </pre>
 *
 * <p>run 이름표에는 그 단계가 실제로 쓰는 바인드가 아니라 <b>스킬의 필수 인자 전량</b>이
 * 들어간다. 그래야 한 절차의 모든 단계가 같은 이름표를 달고, 도착한 스냅샷만 보고
 * "이 절차는 몇 단계까지 왔나"가 유도된다 — 진행을 따로 저장할 필요가 없어진다.
 * (2단계의 바인드 {@code eqp} 는 1단계 결과에서 나오므로 이름표가 될 수 없다.)
 *
 * <p>구분자로 쓰는 {@code # & =} 는 값에서 {@code _} 로 접는다. 키 안에서만 그렇고
 * SQL 에는 원문이 간다. 진행 조회는 파싱이 아니라 <b>키를 다시 만들어 대조</b>하는
 * 방식이라, 접힌 값 때문에 대조가 어긋나지 않는다.
 */
public final class QueryKey {
    private QueryKey() {
    }

    private static final Pattern KEY =
            Pattern.compile("([A-Za-z0-9_.\\-]+)#(\\d{1,3})(?:__(.*))?");

    /** 키에서 읽어 낸 것 — 어느 스킬의 몇 번째 단계인가, 그리고 어느 run 인가. */
    public record Parsed(String skill, int step, String argsPart) {
    }

    /**
     * @param argNames 이름표에 넣을 인자 이름(정렬된 필수 인자). 값이 없는 이름은 건너뛴다.
     */
    public static String of(String skill, int step, Map<String, String> args, List<String> argNames) {
        StringBuilder key = new StringBuilder(skill).append('#').append(step);
        List<String> parts = new java.util.ArrayList<>();
        for (String name : argNames) {
            String value = args != null ? args.get(name) : null;
            if (value != null && !value.isBlank()) {
                parts.add(name + "=" + sanitize(value));
            }
        }
        if (!parts.isEmpty()) {
            key.append("__").append(String.join("&", parts));
        }
        return key.toString();
    }

    /** 풀 형식이 아닌 키(옛 자유 저작 스냅샷 등)는 {@code null} — 진행 유도에서 조용히 빠진다. */
    public static Parsed parse(String queryKey) {
        if (queryKey == null) {
            return null;
        }
        Matcher m = KEY.matcher(queryKey.trim());
        if (!m.matches()) {
            return null;
        }
        return new Parsed(m.group(1), Integer.parseInt(m.group(2)),
                m.group(3) != null ? m.group(3) : "");
    }

    /** 이름표를 다시 인자 맵으로 — 진행 섹션이 "이 인자로 이어 요청하라"를 적을 때 쓴다. */
    public static Map<String, String> parseArgs(String argsPart) {
        Map<String, String> args = new LinkedHashMap<>();
        if (argsPart == null || argsPart.isBlank()) {
            return args;
        }
        for (String pair : argsPart.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                args.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return args;
    }

    /** 구분자 충돌만 막는다 — 값의 다른 문자는 그대로 둔다(사람이 읽는 키다). */
    static String sanitize(String value) {
        return value.trim().replace("#", "_").replace("&", "_").replace("=", "_");
    }
}

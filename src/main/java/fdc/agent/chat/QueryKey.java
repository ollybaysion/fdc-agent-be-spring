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
 * fdc-explain-sensor#sensor_row__snsr_id=S-0004
 * └─ 스킬 ─────────┘ └ 조달 id ┘ └─ run 이름표 ─┘
 * </pre>
 *
 * <p>가운데가 스텝 인덱스가 아니라 <b>조달 수단의 id</b> 다(spec v3). {@code queries[]}
 * 는 카탈로그라 순서에 뜻이 없어져, 위치로 가리키면 목록 재배열만으로 남의 조회를
 * 가리키게 된다.
 *
 * <p>run 이름표에는 그 조달이 실제로 쓰는 바인드가 아니라 <b>스킬의 필수 인자 전량</b>이
 * 들어간다. 그래야 한 절차의 모든 조달이 같은 이름표를 달고, 도착한 스냅샷만 보고
 * "이 절차는 무엇을 알아냈나"가 유도된다 — 진행을 따로 저장할 필요가 없어진다.
 * (앞 조달 결과에서 오는 바인드는 이름표가 될 수 없다.)
 *
 * <p>구분자로 쓰는 {@code # & =} 는 값에서 {@code _} 로 접는다. 키 안에서만 그렇고
 * SQL 에는 원문이 간다. 진행 조회는 파싱이 아니라 <b>키를 다시 만들어 대조</b>하는
 * 방식이라, 접힌 값 때문에 대조가 어긋나지 않는다.
 */
public final class QueryKey {
    private QueryKey() {
    }

    private static final Pattern KEY =
            Pattern.compile("([A-Za-z0-9_.\\-]+)#([A-Za-z][A-Za-z0-9_]*)(?:__(.*))?");

    /** 키에서 읽어 낸 것 — 어느 스킬의 어느 조달인가, 그리고 어느 run 인가. */
    public record Parsed(String skill, String query, String argsPart) {

        /** 풀 주소({@code 스킬명#조달id}) — 인자 없는 정식 id. */
        public String queryId() {
            return skill + "#" + query;
        }
    }

    /**
     * @param argNames 이름표에 넣을 인자 이름(정렬된 필수 인자). 값이 없는 이름은 건너뛴다.
     */
    public static String of(
            String skill, String query, Map<String, String> args, List<String> argNames) {
        StringBuilder key = new StringBuilder(skill).append('#').append(query);
        String part = argsPart(args, argNames);
        if (!part.isEmpty()) {
            key.append("__").append(part);
        }
        return key.toString();
    }

    /**
     * run 이름표만 — 인자 원문({@code runs[].args})으로 선언된 절차를 도착 스냅샷의
     * 키와 같은 표기로 대조할 때 쓴다. 인자가 없으면 빈 문자열.
     */
    public static String argsPart(Map<String, String> args, List<String> argNames) {
        List<String> parts = new java.util.ArrayList<>();
        for (String name : argNames) {
            String value = args != null ? args.get(name) : null;
            if (value != null && !value.isBlank()) {
                parts.add(name + "=" + sanitize(value));
            }
        }
        return String.join("&", parts);
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
        return new Parsed(m.group(1), m.group(2), m.group(3) != null ? m.group(3) : "");
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

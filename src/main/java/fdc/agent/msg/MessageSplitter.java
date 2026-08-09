package fdc.agent.msg;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 붙여넣은 덩어리를 메시지 낱개로 자른다 — <b>결정론</b>이다(#64 다건).
 *
 * <p>여기에 LLM 을 쓰지 않는 이유: 100건이 99건이 되어도 아무도 모른다. 자르는
 * 규칙은 사람이 읽고 고칠 수 있어야 하고, 틀리면 틀린 자리가 보여야 한다.
 *
 * <p>두 모양만 안다. <b>덤프 연속</b>({@code A{...}B{...}})은 최상위 중괄호가
 * 닫히는 자리에서 자르고, <b>로그 줄</b>은 타임스탬프로 시작하는 줄마다 자른다
 * (타임스탬프 없는 줄은 앞 조각의 이어짐이다 — 스택 트레이스·줄바꿈 필드).
 * 둘 다 아니면 자르지 않는다: 한 건으로 보는 게 잘못 자르는 것보다 낫다.
 */
public final class MessageSplitter {

    /** 로그 한 줄의 머리 — {@code 2026-08-08 11:58:03} / {@code 2026-08-08T11:58:03}. */
    private static final Pattern LOG_HEAD =
            Pattern.compile("^\\s*\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}");

    /** 자바 toString 덤프가 <b>어딘가에</b> 들어 있는가 — 로그 줄 안의 것도 센다. */
    private static final Pattern EMBEDDED_DUMP =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*\\s*\\{[\\s\\S]*}");

    private MessageSplitter() {
    }

    /** 낱개로 — 못 자르면 통째 한 건(빈 입력은 빈 목록). */
    public static List<String> split(String pasted) {
        if (pasted == null || pasted.isBlank()) {
            return List.of();
        }
        String one = pasted.strip();
        List<String> dumps = splitDumps(one);
        if (dumps.size() > 1) {
            return dumps;
        }
        List<String> lines = splitLogLines(one);
        if (lines.size() > 1) {
            return lines;
        }
        return List.of(one);
    }

    /** 조각들이 전부 덤프를 품고 있는가 — 표·산문을 다건으로 오인하지 않는 근거. */
    public static boolean allLookLikeMessages(List<String> chunks) {
        return !chunks.isEmpty()
                && chunks.stream().allMatch(c -> EMBEDDED_DUMP.matcher(c).find());
    }

    /**
     * {@code A{...} B{...}} — 중괄호 깊이 0 에서 닫힌 뒤 다음 덤프가 시작하면 자른다.
     * 하나라도 어긋나면(문자열 잡음·괄호 불일치) 빈 목록: 이 모양이 아니라는 뜻이다.
     */
    private static List<String> splitDumps(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean seenOpen = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                depth++;
                seenOpen = true;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    return List.of();
                }
                if (depth == 0 && seenOpen && c == '}') {
                    String chunk = text.substring(start, i + 1).strip();
                    if (!chunk.isEmpty()) {
                        out.add(chunk);
                    }
                    // 조각 사이의 구분은 공백·쉼표·개행뿐이어야 한다.
                    int at = i + 1;
                    while (at < text.length()
                            && (Character.isWhitespace(text.charAt(at)) || text.charAt(at) == ',')) {
                        at++;
                    }
                    start = at;
                    i = at - 1;
                    seenOpen = false;
                }
            }
        }
        // 남은 꼬리가 있으면 덤프 연속이 아니다.
        return depth == 0 && start >= text.length() ? out : List.of();
    }

    /** 타임스탬프로 시작하는 줄마다 새 조각 — 머리 없는 줄은 앞 조각에 붙는다. */
    private static List<String> splitLogLines(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = null;
        for (String line : text.split("\\R")) {
            if (LOG_HEAD.matcher(line).find()) {
                if (current != null) {
                    out.add(current.toString().strip());
                }
                current = new StringBuilder(line);
            } else if (current != null) {
                current.append('\n').append(line);
            } else if (!line.isBlank()) {
                // 머리 없는 첫 줄이 있으면 로그 모양이 아니다.
                return List.of();
            }
        }
        if (current != null) {
            out.add(current.toString().strip());
        }
        return out;
    }
}

package fdc.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SSE 본문 파싱 헬퍼(tokenText/donePayload). */
public final class SseTestSupport {
    private SseTestSupport() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOKEN = Pattern.compile("event: token\ndata: (.+)");
    private static final Pattern DONE = Pattern.compile("event: done\ndata: (.+)");

    /** SSE 본문에서 token 들을 이어붙여 최종 텍스트 복원. */
    public static String tokenText(String body) {
        StringBuilder sb = new StringBuilder();
        Matcher m = TOKEN.matcher(body);
        while (m.find()) {
            try {
                sb.append(JSON.readTree(m.group(1)).path("content").asText());
            } catch (Exception e) {
                throw new IllegalStateException("token 파싱 실패: " + m.group(1), e);
            }
        }
        return sb.toString();
    }

    /** SSE 본문에서 done 페이로드 파싱(없으면 null). */
    public static JsonNode donePayload(String body) {
        Matcher m = DONE.matcher(body);
        if (!m.find()) {
            return null;
        }
        try {
            return JSON.readTree(m.group(1));
        } catch (Exception e) {
            throw new IllegalStateException("done 파싱 실패: " + m.group(1), e);
        }
    }
}

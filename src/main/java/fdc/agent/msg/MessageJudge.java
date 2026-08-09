package fdc.agent.msg;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.contract.FormattedMessage;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.util.Trace;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 붙여넣은 텍스트의 메시지 판정 — #64 MVP.
 *
 * <p>두 겹이다: <b>스니프</b>(결정론, 정규식 수준)가 "메시지처럼 생겼나"만 거르고,
 * 통과하면 <b>LLM 1회</b>가 변환·요약·추출을 전부 한다(설계 결정 8). 스니프의
 * 존재 이유는 UX 하나다 — 표 붙여넣기 경로가 LLM 지연(수 초)을 절대 물지 않게.
 * {@code force}(사용자가 "이건 메시지다"라고 명시)면 스니프를 건너뛴다.
 *
 * <p>실패는 전부 null(불가침) — FE 는 로컬 표 파싱으로 폴백한다(fail-open).
 * 결정론 class-dump 파서·required/warnings 검증은 고도화(#66)로 이월됐다.
 */
public final class MessageJudge {

    /**
     * 붙여넣기 원문 상한 — 이보다 크면 메시지가 아니라 데이터 덩어리다(불가침).
     */
    public static final int PASTED_MAX_CHARS = 32_768;

    /**
     * 포맷팅 지시 상수 — {@code MockLlm} 이 문장이 아니라 이 머리표로 분기를
     * 감지한다({@code NarrationPrompt.NARRATE_INSTRUCTION} 과 같은 규율:
     * 프롬프트 문구를 고쳤다고 목이 깨지면 프롬프트를 못 고치게 된다).
     */
    public static final String FORMAT_INSTRUCTION = "[메시지 포맷팅]";

    /**
     * 메시지 스니프 — 자바 toString 덤프({@code Ident{...}}) 모양.
     * 사내 실물 표본·출처가 확인되면 이 패턴을 교정한다(설계 결정 4).
     */
    private static final Pattern CLASS_DUMP =
            Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$.]*\\s*\\{[\\s\\S]*}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageJudge() {
    }

    /** 메시지 후보인가 — 결정론 스니프. 표·일반 텍스트는 여기서 즉시 걸러진다. */
    public static boolean sniff(String pasted) {
        if (pasted == null) {
            return false;
        }
        String one = pasted.trim();
        return !one.isEmpty()
                && one.length() <= PASTED_MAX_CHARS
                && CLASS_DUMP.matcher(one).matches();
    }

    /**
     * 판정 + 포맷팅 — 후보가 아니거나 LLM 이 유효한 JSON 을 못 내놓으면 null.
     * LLM 응답 파싱 실패는 1회 재시도한다(약한 모델 전제).
     */
    public static FormattedMessage judge(LlmClient llm, String pasted, boolean force) {
        if (pasted == null || pasted.isBlank() || pasted.length() > PASTED_MAX_CHARS) {
            return null;
        }
        if (!force && !sniff(pasted)) {
            return null;
        }
        List<LlmMessage> prompt = List.of(LlmMessage.of(Role.USER, promptText(pasted)));
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Trace.emit("BE→LLM 메시지 포맷팅 요청 (툴 없음)", prompt);
                LlmTurn turn = llm.next(prompt, List.of());
                if (!(turn instanceof LlmTurn.Final fin) || fin.content() == null) {
                    continue;
                }
                FormattedMessage out = parse(fin.content());
                if (out != null) {
                    Trace.emit("LLM→BE 메시지 포맷팅 응답", out);
                    return out;
                }
            } catch (RuntimeException e) {
                Trace.raw("메시지 포맷팅 실패 (불가침 — FE 표 파싱 폴백)", String.valueOf(e));
            }
        }
        return null;
    }

    private static String promptText(String pasted) {
        return FORMAT_INSTRUCTION + """
                 붙여넣은 설비/카프카 메시지 텍스트를 포맷팅하라.
                반드시 아래 형태의 JSON 하나만 출력한다(설명·마크다운 금지):
                {"json": <원문을 데이터 유실 없이 구조화한 객체>,
                 "comment": "<이 메시지가 무엇인지 한 줄 요약 (경고·이상 우선)>",
                 "eqpId": "<설비 id 필드가 보이면 그 값, 없으면 생략>",
                 "className": "<클래스명/전문명이 보이면 그 값, 없으면 생략>"}

                원문:
                """ + pasted;
    }

    /**
     * LLM 출력 → 계약 — 마크다운 코드펜스는 벗겨 준다(약한 모델이 자주 두른다).
     * {@code json} 이 객체가 아니면 실패다: 문자열을 그대로 실으면 FE 의 JSON
     * 뷰가 따옴표 덩어리를 그린다.
     */
    static FormattedMessage parse(String content) {
        String body = stripFences(content);
        try {
            Map<?, ?> map = MAPPER.readValue(body, Map.class);
            Object json = map.get("json");
            if (!(json instanceof Map) && !(json instanceof List)) {
                return null;
            }
            return new FormattedMessage(
                    json,
                    asText(map.get("comment")),
                    asText(map.get("eqpId")),
                    asText(map.get("className")),
                    null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripFences(String content) {
        String one = content.trim();
        if (one.startsWith("```")) {
            int nl = one.indexOf('\n');
            int end = one.lastIndexOf("```");
            if (nl >= 0 && end > nl) {
                one = one.substring(nl + 1, end).trim();
            }
        }
        return one;
    }

    private static String asText(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}

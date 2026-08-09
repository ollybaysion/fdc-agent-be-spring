package fdc.agent.msg;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.contract.FormattedMessage;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.util.Trace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 붙여넣은 텍스트의 메시지 판정 — #64.
 *
 * <p>세 겹이다: <b>스니프</b>(결정론, 정규식 수준)가 "메시지처럼 생겼나"만 거르고,
 * <b>분할</b>({@link MessageSplitter}, 결정론)이 낱개로 자른 뒤, <b>LLM 배치</b>가
 * 변환·요약·추출을 한다(설계 결정 8). 스니프의 존재 이유는 UX 하나다 — 표 붙여넣기
 * 경로가 LLM 지연(수 초)을 절대 물지 않게. {@code force}(사용자가 "이건 메시지다"라고
 * 명시)면 스니프를 건너뛴다.
 *
 * <p><b>건당 한 번씩 부르지 않는다.</b> 100건이면 100회가 아니라 {@link #BATCH_SIZE}
 * 씩 묶어 {@code ceil(100/k)} 회다. 묶음 크기를 정하는 건 입력이 아니라 출력이다 —
 * 한 건의 결과 JSON 이 수백 토큰이라, 한 호출에 담을 수 있는 건수는 모델의 출력
 * 상한으로 정해진다. 같은 클래스가 반복될 때 스키마를 한 번만 배우고 나머지를
 * 결정론으로 변환하는 층은 고도화(#66)다.
 *
 * <p>실패는 조각 단위로 격리된다: 묶음이 깨지면 반으로 갈라 다시 묻고, 끝내 못 받은
 * 조각은 {@code json} 없이 원문만 실어 보낸다 — 100건이 한 건 때문에 통째로 사라지지
 * 않는다. 판정 자체가 성립 안 하면 빈 목록(불가침) — FE 는 로컬 표 파싱으로 폴백한다.
 */
public final class MessageJudge {

    /**
     * 붙여넣기 원문 상한 — 이보다 크면 메시지가 아니라 데이터 덩어리다(불가침).
     * 다건을 받게 되면서 32k 에서 올렸다: 로그 100줄이면 수만 자다.
     */
    public static final int PASTED_MAX_CHARS = 131_072;

    /** 조각 수 상한 — 이보다 많으면 붙여넣기가 아니라 파일 적재다(그건 별건이다). */
    public static final int MAX_CHUNKS = 300;

    /**
     * 한 번의 LLM 호출에 담는 메시지 수.
     *
     * <p>입력이 아니라 <b>출력 예산</b>이 정한다: 한 건의 결과 JSON 이 200~400 토큰이면
     * 열 건에 2천~4천 토큰이고, 그 위로는 모델이 배열을 중간에서 끊는다. 끊긴 배열은
     * 통째로 파싱 실패라 재시도 비용이 크다 — 그래서 여유 있게 잡는다.
     */
    public static final int BATCH_SIZE = 10;

    /**
     * 포맷팅 지시 상수 — {@code MockLlm} 이 문장이 아니라 이 머리표로 분기를
     * 감지한다({@code NarrationPrompt.NARRATE_INSTRUCTION} 과 같은 규율:
     * 프롬프트 문구를 고쳤다고 목이 깨지면 프롬프트를 못 고치게 된다).
     */
    public static final String FORMAT_INSTRUCTION = "[메시지 포맷팅]";

    /** 조각 구분자 — 프롬프트에서 몇 번째 메시지인지 세는 유일한 근거. */
    public static final String CHUNK_MARK = "<<<MSG %d>>>";

    /**
     * 메시지 스니프 — 자바 toString 덤프({@code Ident{...}}) 모양.
     * 사내 실물 표본·출처가 확인되면 이 패턴을 교정한다(설계 결정 4).
     */
    private static final Pattern CLASS_DUMP =
            Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$.]*\\s*\\{[\\s\\S]*}$");

    /**
     * {@code occurredAt} 문지기 — 추출이 아니라 <b>형식 검증</b>이다. 시각도 LLM 이
     * 뽑기로 했으니(설계 결정 8 연장), 모델이 "오전 11시쯤" 같은 문장이나 헛것을 실어도
     * FE 정렬이 뒤집히지 않게 모양만 본다. 통과 못 하면 null — 그 건은 등록 시각으로
     * 줄 선다. 소수초 자릿수는 세지 않는다(원문 정밀도를 그대로 받는 게 계약이다).
     */
    private static final Pattern OCCURRED_AT = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?"
                    + "(Z|[+-]\\d{2}:?\\d{2})?$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageJudge() {
    }

    /** 메시지 후보인가 — 결정론 스니프. 표·일반 텍스트는 여기서 즉시 걸러진다. */
    public static boolean sniff(String pasted) {
        if (pasted == null) {
            return false;
        }
        String one = pasted.trim();
        if (one.isEmpty() || one.length() > PASTED_MAX_CHARS) {
            return false;
        }
        if (CLASS_DUMP.matcher(one).matches()) {
            return true;
        }
        // 여러 건이면 낱개가 덤프를 품고 있어야 한다 — 표는 중괄호가 없어 여기서 진다.
        List<String> chunks = MessageSplitter.split(one);
        return chunks.size() > 1 && MessageSplitter.allLookLikeMessages(chunks);
    }

    /**
     * 진행 알림 — 묶음 하나가 끝날 때마다 불린다. 100건이면 열 번에 걸쳐 수십 초라,
     * 다 끝나고 한꺼번에 답하면 화면이 죽은 것처럼 보인다. 분할 직후 {@code (0, n)}
     * 으로 한 번 불러 총량을 먼저 알린다.
     */
    @FunctionalInterface
    public interface Progress {
        void at(int done, int total);
    }

    /**
     * 판정 + 포맷팅 — 조각 순서대로 전량을 돌려준다(변환 실패 조각도 원문은 실린다).
     * 후보가 아니거나 상한을 넘으면 빈 목록이다.
     */
    public static List<FormattedMessage> judgeAll(LlmClient llm, String pasted, boolean force) {
        return judgeAll(llm, pasted, force, (done, total) -> {
        });
    }

    /** 진행을 흘리며 판정 — SSE 로 중간 상태를 보내는 인렛이 쓴다. */
    public static List<FormattedMessage> judgeAll(
            LlmClient llm, String pasted, boolean force, Progress progress) {
        if (pasted == null || pasted.isBlank() || pasted.length() > PASTED_MAX_CHARS) {
            return List.of();
        }
        if (!force && !sniff(pasted)) {
            return List.of();
        }
        List<String> chunks = MessageSplitter.split(pasted);
        if (chunks.isEmpty() || chunks.size() > MAX_CHUNKS) {
            // 말없이 자르지 않는다 — 상한을 넘으면 이 왕복은 성립하지 않는다.
            Trace.raw("메시지 분할 상한 초과 (불가침)", chunks.size() + "건");
            return List.of();
        }
        FormattedMessage[] out = new FormattedMessage[chunks.size()];
        progress.at(0, chunks.size());
        formatRange(llm, chunks, indexesOf(chunks.size()), out, true, progress);

        List<FormattedMessage> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            // 못 받은 조각도 자리를 지킨다 — 원문이 진실원이라 화면에는 설 수 있다.
            result.add(out[i] != null ? out[i] : new FormattedMessage(
                    null, null, null, null, null, null, chunks.get(i), null));
        }
        return result;
    }

    private static List<Integer> indexesOf(int size) {
        List<Integer> all = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            all.add(i);
        }
        return all;
    }

    /**
     * 한 묶음을 묻고, 못 받은 조각만 반으로 갈라 다시 묻는다.
     *
     * <p>모델이 배열을 끊거나 한 건에서 넘어지면 그 묶음 전체가 파싱 실패다. 그때
     * 통째로 포기하면 아홉 건이 한 건 때문에 죽고, 전량을 1건씩 다시 물으면 호출이
     * 폭발한다 — 반씩 좁히면 실패한 자리만 값을 치른다({@code log2}).
     */
    private static void formatRange(
            LlmClient llm, List<String> chunks, List<Integer> want,
            FormattedMessage[] out, boolean top, Progress progress) {
        if (want.isEmpty()) {
            return;
        }
        if (top) {
            for (int at = 0; at < want.size(); at += BATCH_SIZE) {
                List<Integer> batch = want.subList(at, Math.min(at + BATCH_SIZE, want.size()));
                askBatch(llm, chunks, batch, out);
                retryMissing(llm, chunks, batch, out, progress);
                // 이 묶음까지의 진척 — 재시도로 늦어져도 끝난 만큼은 바로 알린다.
                progress.at(Math.min(at + BATCH_SIZE, want.size()), chunks.size());
            }
            return;
        }
        askBatch(llm, chunks, want, out);
        retryMissing(llm, chunks, want, out, progress);
    }

    private static void retryMissing(
            LlmClient llm, List<String> chunks, List<Integer> batch,
            FormattedMessage[] out, Progress progress) {
        List<Integer> missing = batch.stream().filter(i -> out[i] == null).toList();
        if (missing.isEmpty()) {
            return;
        }
        if (missing.size() == 1) {
            // 한 건짜리 묶음까지 왔다 — 한 번만 더 묻고, 그래도 안 되면 원문으로 남는다.
            askBatch(llm, chunks, missing, out);
            return;
        }
        int half = missing.size() / 2;
        formatRange(llm, chunks, missing.subList(0, half), out, false, progress);
        formatRange(llm, chunks, missing.subList(half, missing.size()), out, false, progress);
    }

    /** 묶음 하나를 LLM 에 묻고 받은 것만 채운다 — 못 받은 자리는 호출자가 다시 쪼갠다. */
    private static void askBatch(
            LlmClient llm, List<String> chunks, List<Integer> batch, FormattedMessage[] out) {
        List<LlmMessage> prompt =
                List.of(LlmMessage.of(Role.USER, promptText(chunks, batch)));
        try {
            Trace.emit("BE→LLM 메시지 포맷팅 요청 " + batch.size() + "건 (툴 없음)", prompt);
            LlmTurn turn = llm.next(prompt, List.of());
            if (!(turn instanceof LlmTurn.Final fin) || fin.content() == null) {
                return;
            }
            Map<Integer, FormattedMessage> parsed = parseBatch(fin.content());
            for (int i : batch) {
                FormattedMessage one = parsed.get(i);
                if (one != null) {
                    out[i] = withRaw(one, chunks.get(i));
                }
            }
            Trace.emit("LLM→BE 메시지 포맷팅 응답 " + parsed.size() + "건", parsed.values());
        } catch (RuntimeException e) {
            Trace.raw("메시지 포맷팅 실패 (남은 조각은 다시 쪼개 묻는다)", String.valueOf(e));
        }
    }

    private static FormattedMessage withRaw(FormattedMessage one, String raw) {
        return new FormattedMessage(one.json(), one.comment(), one.eqpId(), one.className(),
                one.title(), one.occurredAt(), raw, one.docId());
    }

    private static String promptText(List<String> chunks, List<Integer> batch) {
        StringBuilder body = new StringBuilder(FORMAT_INSTRUCTION);
        body.append("""
                 붙여넣은 설비/카프카 메시지들을 각각 포맷팅하라.
                반드시 아래 형태의 JSON 배열 하나만 출력한다(설명·마크다운 금지):
                [{"index": <입력 번호 그대로>,
                  "json": <원문을 데이터 유실 없이 구조화한 객체>,
                  "comment": "<이 메시지가 무엇인지 한 줄 요약 (경고·이상 우선)>",
                  "eqpId": "<설비 id 필드가 보이면 그 값, 없으면 생략>",
                  "className": "<클래스명/전문명이 보이면 그 값, 없으면 생략>",
                  "title": "<목록에서 이 한 건을 알아볼 20자 안팎의 이름. lot/alarm/step
                            같은 식별 값을 쓴다. 클래스명만 반복하지 말 것>",
                  "occurredAt": "<메시지 안에 찍힌 발생 시각을 yyyy-MM-ddTHH:mm:ss.FFFFFF
                                 형태로. 소수초는 원문에 적힌 자릿수 그대로 옮기고 자르거나
                                 반올림하지 말 것. 시각이 없으면 생략>"}]

                입력 개수와 같은 수의 원소를 내고, index 는 아래 번호를 그대로 쓴다.

                """);
        for (int i : batch) {
            body.append(CHUNK_MARK.formatted(i)).append('\n')
                    .append(chunks.get(i)).append('\n');
        }
        return body.toString();
    }

    /**
     * LLM 출력 → 계약 — 마크다운 코드펜스는 벗겨 준다(약한 모델이 자주 두른다).
     * 배열이 아니면(단일 객체) 한 건짜리 배열로 받아 준다.
     */
    static Map<Integer, FormattedMessage> parseBatch(String content) {
        String body = stripFences(content);
        Map<Integer, FormattedMessage> out = new java.util.LinkedHashMap<>();
        try {
            Object root = MAPPER.readValue(body, Object.class);
            List<?> rows = root instanceof List<?> list ? list : List.of(root);
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> map)) {
                    continue;
                }
                Integer index = asIndex(map.get("index"));
                FormattedMessage one = fromMap(map);
                if (index != null && one != null) {
                    out.put(index, one);
                }
            }
        } catch (Exception e) {
            return Map.of();
        }
        return out;
    }

    /**
     * 한 원소 → 계약. {@code json} 이 객체가 아니면 실패다: 문자열을 그대로 실으면
     * FE 의 JSON 뷰가 따옴표 덩어리를 그린다.
     */
    private static FormattedMessage fromMap(Map<?, ?> map) {
        Object json = map.get("json");
        if (!(json instanceof Map) && !(json instanceof List)) {
            return null;
        }
        return new FormattedMessage(
                json,
                asText(map.get("comment")),
                asText(map.get("eqpId")),
                asText(map.get("className")),
                asText(map.get("title")),
                asOccurredAt(map.get("occurredAt")),
                null,
                null);
    }

    /** 번호가 없거나 숫자가 아니면 버린다 — 자리를 모르는 결과는 쓸 수 없다. */
    private static Integer asIndex(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 모양이 맞는 시각만 통과 — 나머지는 null(그 건은 등록 시각으로 줄 선다). */
    static String asOccurredAt(Object value) {
        String text = asText(value);
        return text != null && OCCURRED_AT.matcher(text.trim()).matches() ? text.trim() : null;
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

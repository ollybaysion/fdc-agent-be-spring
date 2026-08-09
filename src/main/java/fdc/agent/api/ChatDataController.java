package fdc.agent.api;

import fdc.agent.chat.AltFillJudge;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.chat.NarrationPrompt;
import fdc.agent.chat.PanelJudge;
import fdc.agent.config.ApiException;
import fdc.agent.config.AppProps;
import fdc.agent.contract.ChatDataDone;
import fdc.agent.contract.FormattedMessage;
import fdc.agent.contract.QueryScope;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.msg.MessageJudge;
import fdc.agent.schema.SchemaSource;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillSource;
import fdc.agent.skills.SkillSpec;
import fdc.agent.util.Trace;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/fdc/v1/chat/data — 데이터 패널 판정 인렛(#38, 코드네임 panel-judge).
 * 패널의 모든 수정·입력이 이 인렛을 부르고, {@link PanelJudge} 가 결정론으로
 * 진행·종결을 판정한다. 응답에는 <b>조달 원장</b>이 함께 나간다 — 그 절차의 조달
 * 전량이 상태를 달고 매번 전부 실리고 FE 는 replace 한다. 절차 종결 전이만
 * 그 자리에서 SSE 로 최종 서술을 스트리밍한다(합성 사용자 발화 없음, LLM 1회).
 *
 * <p>응답은 항상 SSE — 서술이 있으면 token* → done, 아니면 done 만.
 * 판정 실패는 스트리밍 시작 전이라 정상 HTTP 상태로 나간다({@code /chat} 과
 * 같은 규율). 대화 라인의 인렛은 전부 {@code /chat/*} 네임스페이스다 — 조달이
 * 별도 채널이 아니라 대화의 일부라는 Inline 명제를 URL 이 그대로 표현한다.
 */
@RestController
public class ChatDataController {

    private static final Logger log = LoggerFactory.getLogger(ChatDataController.class);

    private final LlmClient llm;
    private final SkillSource skillSource;
    private final SchemaSource schemaSource;
    private final AppProps props;

    public ChatDataController(
            LlmClient llm, SkillSource skillSource, SchemaSource schemaSource, AppProps props) {
        this.llm = llm;
        this.skillSource = skillSource;
        this.schemaSource = schemaSource;
        this.props = props;
    }

    @PostMapping("/api/fdc/v1/chat/data")
    public void chatData(
            @RequestBody(required = false) PanelJudge.PanelBody body, HttpServletResponse res)
            throws IOException {
        if (body == null) {
            SseSupport.writeJson(res, 400, Map.of("error", "body_required"));
            return;
        }
        List<HistoryMessage> messages = ChatController.knownRoles(
                body.messages() != null ? body.messages() : List.of());
        Map<String, Object> limitError = ChatController.messageLimitError(messages);
        if (limitError != null) {
            SseSupport.writeJson(res, 400, limitError);
            return;
        }

        // 메시지 판정 왕복(#64) — pasted 가 실리면 이 왕복은 패널 판정이 아니다.
        // 스니프 → 결정론 분할 → LLM 배치로 formattedMessages 만 답하고 끝낸다.
        // 빈 목록이면 메시지가 아니었다는 뜻(불가침) — FE 는 표 파싱으로 폴백한다.
        if (body.pasted() != null && !body.pasted().isBlank()) {
            respondMessageJudge(body, res);
            return;
        }

        traceRequest(body, messages);

        List<SkillSpec> specs = skillSource.specs();
        QueryPool pool = QueryPool.of(specs);
        PanelJudge.Verdict verdict;
        try {
            verdict = judgeWithFallbacks(pool, body);
        } catch (Exception err) {
            log.error("chat/data judge error", err);
            int statusCode = err instanceof ApiException api ? api.status() : 500;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "chat_data_failed");
            out.put("message", props.isProd() || err.getMessage() == null
                    ? "chat/data failed"
                    : err.getMessage());
            SseSupport.writeJson(res, statusCode, out);
            return;
        }

        // 종결 서술 — best-effort: LLM 이 실패해도 판정(카드·진행)은 그대로 나간다.
        String text = verdict.narration() != null
                ? narrate(messages, body.scope(), specs, verdict.narration())
                : null;

        String messageId = "msg_" + Long.toString(System.currentTimeMillis(), 36);
        ChatDataDone done = new ChatDataDone(
                messageId,
                body.eventId(),
                body.revision(),
                pool.rev(),
                verdict.ledger(),
                verdict.runsProgress(),
                emptyToNull(verdict.terminalRuns()),
                text != null ? verdict.narration().runLabel() : null,
                null,
                null);

        Map<String, Object> traceOut = new LinkedHashMap<>();
        traceOut.put("text", text);
        traceOut.put("done", done);
        Trace.emit("BE→FE 응답 (chat/data SSE token* + done)", traceOut);

        SseSupport.sseHeaders(res);
        ServletOutputStream out = res.getOutputStream();
        try {
            if (text != null && !text.isEmpty()) {
                int[] codePoints = text.codePoints().toArray();
                long interval = props.chat().intervalFor(codePoints.length);
                for (int cp : codePoints) {
                    String ch = new String(Character.toChars(cp));
                    SseSupport.write(out, SseSupport.sse("token", Map.of("content", ch)));
                    res.flushBuffer();
                    SseSupport.sleep(interval);
                }
            }
            SseSupport.write(out, SseSupport.sse("done", done));
        } catch (Exception err) {
            log.error("chat/data stream error", err);
            SseSupport.write(out, SseSupport.sse("error", Map.of("message", "stream error")));
        } finally {
            res.flushBuffer();
        }
    }

    /**
     * 메시지 판정 왕복의 응답 — token 스트림 없이 done 하나. 원장(dataRequests)을
     * 싣지 않는 이유는 이 왕복이 패널 상태 변경이 아니라서다: FE 도 이 응답으로
     * 원장을 replace 하지 않는다.
     */
    private void respondMessageJudge(PanelJudge.PanelBody body, HttpServletResponse res)
            throws IOException {
        if (Trace.on()) {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("eventId", body.eventId());
            in.put("pastedChars", body.pasted().length());
            in.put("pastedForce", body.pastedForce());
            Trace.emit("FE→BE 메시지 판정 POST /api/fdc/v1/chat/data (pasted)", in);
        }
        // 진행을 흘리려면 헤더가 먼저다 — 100건이면 열 번에 걸쳐 수십 초라, 다 끝나고
        // 한꺼번에 답하면 그동안 화면이 죽은 것처럼 보인다.
        SseSupport.sseHeaders(res);
        ServletOutputStream out = res.getOutputStream();
        List<FormattedMessage> formatted = MessageJudge.judgeAll(
                llm,
                body.pasted(),
                Boolean.TRUE.equals(body.pastedForce()),
                (count, total) -> writeProgress(res, out, count, total));
        // 단수 필드는 한 건이면서 변환까지 된 경우에만 — 변환 실패를 실어 보내면
        // 배열을 아직 안 읽는 FE 가 json 없는 카드를 세우려다 넘어진다(폴백이 막힌다).
        FormattedMessage single =
                formatted.size() == 1 && formatted.get(0).json() != null ? formatted.get(0) : null;
        ChatDataDone done = new ChatDataDone(
                "msg_" + Long.toString(System.currentTimeMillis(), 36),
                body.eventId(),
                body.revision(),
                null,
                null,
                null,
                null,
                null,
                single,
                formatted.isEmpty() ? null : formatted);
        Trace.emit("BE→FE 응답 (chat/data done, formattedMessages " + formatted.size() + "건)", done);
        SseSupport.write(out, SseSupport.sse("done", done));
        res.flushBuffer();
    }

    /**
     * 진행 한 줄 — {@code progress {done, total}}. 쓰기가 실패해도(연결이 끊겼다)
     * 판정은 계속한다: 진행 표시가 본문을 죽이면 안 된다.
     */
    private static void writeProgress(
            HttpServletResponse res, ServletOutputStream out, int count, int total) {
        try {
            SseSupport.write(out, SseSupport.sse(
                    "progress", Map.of("done", count, "total", total)));
            res.flushBuffer();
        } catch (IOException e) {
            Trace.raw("진행 전송 실패 (판정은 계속)", String.valueOf(e));
        }
    }

    /**
     * 판정 — 결정론 먼저, 그것으로 안 닿은 자리만 LLM 에 묻는다(채움 폭포).
     *
     * <p>1·2차({@link PanelJudge})는 무상태 순수함수라 언제나 돈다. 3차
     * ({@link AltFillJudge})는 <b>못 채운 need 와 결정론이 못 쓴 표가 동시에 있을 때만</b>
     * 부르고, 새로 채워진 것이 있으면 그것을 얹어 다시 판정한다 — 두 번째 판정도
     * 같은 결정론 규칙이고, LLM 이 바꾼 것은 입력(무엇이 채워졌나)뿐이다.
     */
    private PanelJudge.Verdict judgeWithFallbacks(QueryPool pool, PanelJudge.PanelBody body) {
        PanelJudge.Verdict deterministic = PanelJudge.judge(pool, body);
        Map<String, List<AltFillJudge.AltFill>> altFills =
                AltFillJudge.ask(llm, pool, body, deterministic.runsProgress());
        return altFills.isEmpty() ? deterministic : PanelJudge.judge(pool, body, altFills);
    }

    /**
     * 종결 서술 한 문단 — {@code runLoop} 가 아니라 툴 없는 단일 호출(T6·T12)이고,
     * 프롬프트는 {@link NarrationPrompt} 가 v3 로 합성한다(#51): 정체성 + 이력 +
     * 맥락 섹션(질의 대상·데이터 전량·답변 가이드) + 지시.
     */
    private String narrate(List<HistoryMessage> messages, QueryScope scope,
            List<SkillSpec> specs, PanelJudge.Narration narration) {
        SkillSpec spec = specs.stream()
                .filter(s -> s != null && narration.skill().equals(s.name()))
                .findFirst()
                .orElse(null);
        List<LlmMessage> prompt =
                NarrationPrompt.messages(messages, scope, spec, narration, schemaSource);
        try {
            Trace.emit("BE→LLM 종결 서술 요청 (툴 없음)", prompt);
            LlmTurn turn = llm.next(prompt, List.of());
            Trace.emit("LLM→BE 종결 서술 응답", turn);
            return turn instanceof LlmTurn.Final fin ? fin.content() : null;
        } catch (RuntimeException e) {
            Trace.raw("종결 서술 실패 (판정 결과는 그대로 나간다)", String.valueOf(e));
            return null;
        }
    }

    /** 요청 트레이스 — 스냅샷 rows 만 앞부분으로 줄인다({@code /chat} 과 같은 방식). */
    private static void traceRequest(PanelJudge.PanelBody body, List<HistoryMessage> messages) {
        if (!Trace.on()) {
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", body.eventId());
        out.put("revision", body.revision());
        out.put("event", body.event());
        out.put("runs", body.runs());
        out.put("snapshotIndex", body.snapshotIndex());
        out.put("snapshots", body.snapshots() == null
                ? null
                : body.snapshots().stream().map(ChatController::traceSnapshot).toList());
        out.put("scope", body.scope());
        out.put("inputs", body.inputs());
        out.put("messages", messages.size() + "개");
        Trace.emit("FE→BE 요청 POST /api/fdc/v1/chat/data", out);
    }

    private static List<String> emptyToNull(List<String> list) {
        return list == null || list.isEmpty() ? null : list;
    }
}

package fdc.agent.api;

import fdc.agent.chat.BranchPrompt;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.chat.NarrationPrompt;
import fdc.agent.chat.PanelJudge;
import fdc.agent.config.ApiException;
import fdc.agent.config.AppProps;
import fdc.agent.contract.BranchDecision;
import fdc.agent.contract.ChatDataDone;
import fdc.agent.contract.QueryScope;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmTurn;
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
 * 진행·종결을 판정한다. 요청 카드는 응답에 없다 — 카드 배치·SQL 완성은 FE 가
 * 카탈로그(binds 포함)로 로컬 판정한다(demo-fe dataList). 절차 종결 전이만
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
    private final AppProps props;

    public ChatDataController(LlmClient llm, SkillSource skillSource, AppProps props) {
        this.llm = llm;
        this.skillSource = skillSource;
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

        traceRequest(body, messages);

        List<SkillSpec> specs = skillSource.specs();
        QueryPool pool = QueryPool.of(specs);
        PanelJudge.Verdict verdict;
        BranchDecision fresh = null;
        try {
            verdict = PanelJudge.judge(pool, body);
            // 분기 판정 — 분기 있는 스텝의 도착이면 LLM 1회. 성립(stop/open)이면 그
            // 사실을 넣어 재판정하고, continue·실패는 분기 없던 동작 그대로다(#55).
            if (verdict.branchQuestion() != null) {
                fresh = judgeBranch(specs, pool, verdict.branchQuestion());
                if (fresh != null) {
                    verdict = PanelJudge.judge(pool, body, fresh);
                }
            }
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
        List<BranchDecision> decisions = new java.util.ArrayList<>(
                body.branchDecisions() != null ? body.branchDecisions() : List.of());
        if (fresh != null) {
            decisions.add(fresh);
        }
        ChatDataDone done = new ChatDataDone(
                messageId,
                body.eventId(),
                body.revision(),
                pool.rev(),
                verdict.runsProgress(),
                emptyToNull(verdict.terminalRuns()),
                emptyToNull(decisions),
                text != null ? verdict.narration().runLabel() : null);

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
        List<LlmMessage> prompt = NarrationPrompt.messages(messages, scope, spec, narration);
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

    /**
     * 분기 판정 한 번 — 툴 없는 단일 호출, 판정 본문 user 메시지 하나(#55, 문안은
     * {@code docs/branch-prompt.md}). 성립(stop/open)만 사실로 만들고, continue·
     * 실패·계약 밖 응답은 전부 null — 분기 없던 동작으로 떨어진다(best-effort).
     */
    private BranchDecision judgeBranch(
            List<SkillSpec> specs, QueryPool pool, PanelJudge.BranchQuestion question) {
        SkillSpec spec = specs.stream()
                .filter(s -> s != null && question.skill().equals(s.name()))
                .findFirst()
                .orElse(null);
        List<LlmMessage> prompt = BranchPrompt.messages(BranchPrompt.body(
                spec, question.skill(), question.args(), question.query(),
                pool.stepsOf(question.skill()), question.data()));
        try {
            Trace.emit("BE→LLM 분기 판정 요청 (툴 없음)", prompt);
            LlmTurn turn = llm.next(prompt, List.of());
            Trace.emit("LLM→BE 분기 판정 응답", turn);
            String content = turn instanceof LlmTurn.Final fin ? fin.content() : null;
            BranchPrompt.Call call = BranchPrompt.parse(content, question.query().branches());
            if (call == null) {
                return null;
            }
            return new BranchDecision(
                    question.skill(), question.args(), question.query().step(),
                    call.decision(), call.index(),
                    call.reason() == null || call.reason().isBlank() ? null : call.reason().trim());
        } catch (RuntimeException e) {
            Trace.raw("분기 판정 실패 (continue 로 강등)", String.valueOf(e));
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
        out.put("branchDecisions", body.branchDecisions());
        out.put("scope", body.scope());
        out.put("inputs", body.inputs());
        out.put("messages", messages.size() + "개");
        Trace.emit("FE→BE 요청 POST /api/fdc/v1/chat/data", out);
    }

    private static <T> List<T> emptyToNull(List<T> list) {
        return list == null || list.isEmpty() ? null : list;
    }
}

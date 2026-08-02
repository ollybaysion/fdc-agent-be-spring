package fdc.agent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.chat.PanelJudge.Narration;
import fdc.agent.chat.PanelJudge.StepArrival;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.QueryScope;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.skills.SkillLoader;
import fdc.agent.skills.SkillSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 종결 서술 호출의 v3 프롬프트 합성(#51, QT1 정본 경로) — <b>툴 없는 단일 왕복,
 * 적재 데이터 전량 동봉</b>. 조회 수단이 없으므로 모델이 스스로 데이터를 당길 수
 * 없고, 그래서 맥락 섹션이 데이터의 전부를 실어야 한다.
 *
 * <p>맥락 섹션 = 마지막에 끼우는 system 메시지 하나, markdown 문서 한 편.
 * <b>절 하나 = 합성 함수 하나</b>(전부 결정론)이고, 절 헤딩 문자열({@code SECTION_*})
 * 이 테스트·목의 앵커다 — 문장은 바뀌어도 헤딩은 유지된다.
 *
 * <p>전체는 (도착 실물 {@link Narration}, spec, 질의 대상, 이력)의 순수 함수다.
 * 컬럼 의미 절(akg db-schema 발췌, #49)은 발췌 경로가 생기면 데이터 절 뒤에
 * 들어온다 — 지금은 절 자체가 없다.
 */
public final class NarrationPrompt {
    private NarrationPrompt() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 맥락 섹션 절 헤딩 — 이 문자열이 테스트 앵커다. */
    public static final String SECTION_TARGET = "# 질의 대상";
    public static final String SECTION_DATA = "# 데이터";
    public static final String SECTION_GUIDE = "# 답변 가이드";

    /**
     * 종결 서술 지시 — 마지막 user 메시지의 전문. 목({@code MockLlm})과 테스트는
     * 문장이 아니라 이 상수에 붙는다: 문구를 고쳐도 상수를 참조하는 쪽은 안 깨진다.
     */
    public static final String NARRATE_INSTRUCTION =
            "방금 도착한 데이터로 조회 절차가 완료됐다. 처음 질문의 의도에 맞춰, 맥락의 "
                    + SECTION_DATA + " 를 근거로 결론을 서술하라.";

    /**
     * 정체성 — 툴 규율이 없다(단일 왕복이라 조회 수단 자체가 없다). 서식 문장은
     * {@link ChatPrompt} 의 IDENTITY 와 같은 이유다: FE 가 Markdown(GFM)으로 렌더한다.
     */
    static final String IDENTITY = String.join(" ",
            "당신은 반도체 설비 이상탐지(FDC) 분석 어시스턴트다.",
            "제공되지 않은 값은 지어내지 않는다.",
            "답변은 한국어로 간결하게 작성하고, 서식은 Markdown(GFM)으로 한다 —",
            "강조는 **굵게**, 구조가 필요하면 목록(-)·인용(>)을 쓰되 원시 HTML 은 쓰지 않는다.",
            "제공된 데이터 표는 화면에도 표시되니 본문에 같은 표를 다시 그리지 말고 해석·요약에 집중한다.");

    /**
     * 종결 서술 한 요청의 메시지 전부 — 정체성 system, 대화 이력 그대로, 맥락
     * system, 지시 user 로 마감(assistant 로 끝나는 이력을 GW 에 보내지 않는다).
     *
     * @param spec 서술 대상 run 의 스킬 spec — 답변 가이드의 재료. null 이면(풀과
     *     spec 목록이 어긋난 경우) 가이드 절 없이 데이터만으로 서술한다.
     */
    public static List<LlmMessage> messages(
            List<HistoryMessage> history, QueryScope scope, SkillSpec spec, Narration narration) {
        List<LlmMessage> out = new ArrayList<>();
        out.add(LlmMessage.of(Role.SYSTEM, IDENTITY));
        for (HistoryMessage m : history != null ? history : List.<HistoryMessage>of()) {
            out.add(LlmMessage.of(
                    m.role() == Role.ASSISTANT ? Role.ASSISTANT : Role.USER,
                    m.content() != null ? m.content() : ""));
        }
        out.add(LlmMessage.of(Role.SYSTEM, contextSection(scope, spec, narration)));
        out.add(LlmMessage.of(Role.USER, NARRATE_INSTRUCTION));
        return out;
    }

    /** 맥락 섹션 전문 — 절들을 빈 줄로 잇는다. 컬럼 의미 절은 #49 가 들어올 자리다. */
    static String contextSection(QueryScope scope, SkillSpec spec, Narration narration) {
        return ChatPrompt.join(
                queryTarget(scope, spec, narration),
                dataSection(narration),
                answerGuide(spec, narration));
    }

    /**
     * {@code # 질의 대상} — 이 서술이 무엇을 놓고 하는 것인지 한 줄. 사용자가 담은
     * 분석 카드(scope)가 있으면 그 설비·초점을 쓰고, 없으면 run 정체(스킬+인자)만.
     */
    static String queryTarget(QueryScope scope, SkillSpec spec, Narration narration) {
        QueryScope.Analysis analysis = matchAnalysis(scope, narration);
        String focus = analysis != null && analysis.focus() != null && !analysis.focus().isBlank()
                ? analysis.focus().trim()
                : spec != null && spec.focus() != null && !spec.focus().isBlank()
                        ? spec.focus()
                        : narration.skill();
        List<String> detail = new ArrayList<>();
        detail.add(narration.skill());
        for (Map.Entry<String, String> kv : sorted(narration.args()).entrySet()) {
            detail.add(kv.getKey() + "=" + kv.getValue());
        }
        String line = focus + " (" + String.join("; ", detail) + ")";
        if (analysis != null && analysis.equipment() != null && !analysis.equipment().isBlank()) {
            line = "설비 " + analysis.equipment().trim() + " · " + line;
        }
        return SECTION_TARGET + "\n- " + line;
    }

    /** scope 의 분석 중 이 run 과 같은 것 — 스킬이 같고 조회 키가 어긋나지 않는 첫 항목. */
    private static QueryScope.Analysis matchAnalysis(QueryScope scope, Narration narration) {
        if (scope == null || scope.analyses() == null) {
            return null;
        }
        for (QueryScope.Analysis a : scope.analyses()) {
            if (a == null || a.skill() == null || !a.skill().trim().equals(narration.skill())) {
                continue;
            }
            boolean argsAgree = true;
            for (Map.Entry<String, String> kv
                    : (a.inputs() != null ? a.inputs() : Map.<String, String>of()).entrySet()) {
                if (kv.getValue() == null || kv.getValue().isBlank()) {
                    continue;
                }
                if (!kv.getValue().trim().equals(narration.args().get(kv.getKey()))) {
                    argsAgree = false;
                    break;
                }
            }
            if (argsAgree) {
                return a;
            }
        }
        return null;
    }

    /**
     * {@code # 데이터} — 도착 스냅샷 <b>전량 동봉</b>(행 상한 없음). 블록 헤딩은
     * {@code ## <원천 테이블명> — <스텝 라벨> (N행)} — 테이블명이 컬럼 의미 절(#49)과
     * 짝을 맞출 키다. 0행 표는 행이 없어도 블록으로 남긴다: 조회했으나 없음이
     * 확인된 것은 사실이고, 답의 근거다.
     */
    static String dataSection(Narration narration) {
        List<String> parts = new ArrayList<>();
        parts.add(SECTION_DATA + "\n"
                + "데이터가 있는 표는 전부 아래 실려 있다 — 여기 없는 값은 조회되지 않은 것이다.\n"
                + "행이 0 인 표는 조회했으나 없음이 확인된 것이다 — 없다는 사실로 답하라.");
        for (StepArrival step : narration.steps()) {
            parts.add(dataBlock(step));
        }
        return String.join("\n\n", parts);
    }

    private static String dataBlock(StepArrival step) {
        String table = step.query().table() != null ? step.query().table() : "조회 결과";
        String label = stepLabel(step.query().title());
        if (step.hit().isEmptyResult()) {
            return "## " + table + " — " + label + " (0행)\n"
                    + "조회 결과 0행 — 데이터가 없음이 확인됐다.";
        }
        ChatDataSnapshot full = step.full();
        Integer rowCount = step.hit().rowCount();
        String head = "## " + table + " — " + label + " (" + rowCount + "행)";
        if (full == null || !full.hasRows()) {
            // 판정 집합에는 도착으로 잡혔는데 rows 실물이 이 요청에 안 실린 경우 —
            // 값을 지어내지 않고 없는 사실을 적는다.
            return head + "\n행 전문이 이 요청에 실리지 않았다 — 값은 데이터 패널에 있다.";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("columns", full.columns() != null ? full.columns() : step.hit().columns());
        body.put("rows", full.rows());
        return head + "\n```json\n" + stringify(body) + "\n```";
    }

    /** 스텝 라벨 — {@code "2단계 — 소속 설비"} 의 순번 머리를 뗀다(순번은 헤딩 소음). */
    static String stepLabel(String title) {
        if (title == null || title.isBlank()) {
            return "조회";
        }
        String label = title.replaceFirst("^\\s*\\d+\\s*단계\\s*[—–-]\\s*", "").trim();
        return label.isEmpty() ? title.trim() : label;
    }

    /**
     * {@code # 답변 가이드} — 전부 spec 에서 결정론 합성: 머리문장 =
     * {@code scope.단위}+{@code focus}(조사 규칙은 {@link SkillLoader} 와 동일),
     * 반드시 포함 = 도착 스텝의 {@code produces}, 하지 말 것 = {@code output.avoid}.
     */
    static String answerGuide(SkillSpec spec, Narration narration) {
        if (spec == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (spec.scope() != null && spec.scope().단위() != null
                && spec.focus() != null && !spec.focus().isBlank()) {
            String particle = SkillLoader.hasFinalConsonant(spec.focus()) ? "을" : "를";
            parts.add(SECTION_GUIDE + "\n조회한 데이터로 " + spec.scope().단위() + "의 "
                    + spec.focus() + particle + " 설명한다.");
        } else {
            parts.add(SECTION_GUIDE);
        }

        List<String> produces = narration.steps().stream()
                .map(s -> s.query().produces())
                .filter(p -> p != null && !p.isBlank())
                .toList();
        if (!produces.isEmpty()) {
            List<String> lines = new ArrayList<>();
            lines.add("반드시 포함 (질문이 특정 항목만 묻는 게 아니면):");
            for (String p : produces) {
                lines.add("- " + p);
            }
            parts.add(String.join("\n", lines));
        }

        if (spec.output() != null && spec.output().avoid() != null
                && !spec.output().avoid().isEmpty()) {
            List<String> lines = new ArrayList<>();
            lines.add("하지 말 것:");
            for (String a : spec.output().avoid()) {
                lines.add("- " + a);
            }
            parts.add(String.join("\n", lines));
        }
        return String.join("\n\n", parts);
    }

    /** 인자 순서를 키 정렬로 고정 — 같은 run 은 같은 문장이 나온다(순수 함수 전제). */
    private static Map<String, String> sorted(Map<String, String> args) {
        return args != null ? new TreeMap<>(args) : Map.of();
    }

    private static String stringify(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}

package fdc.agent.chat;

import fdc.agent.chat.ChatAgent.HistoryMessage;
import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.QueryScope;
import fdc.agent.contract.Role;
import fdc.agent.llm.LlmTypes.LlmMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 에 보낼 프롬프트 조립 — 한 요청의 메시지 목록을 여기서 다 만든다.
 * 에이전트 루프({@link ChatAgent})는 "무슨 말을 어떻게 적을지"를 모른다.
 *
 * <p>프롬프트는 두 조각이다:
 *
 * <ul>
 *   <li><b>시스템 프롬프트</b> = 정체성·공통 규율 + <b>붙어 있는 툴들이 스스로 내놓은
 *       사용 규율</b>({@link AgentTool#guidance()}). 툴 규율을 여기 손으로 적지 않는
 *       이유는 그렇게 하면 툴이 빠진 요청에도 규율만 남아, 없는 툴을 쓰라고 지시하게
 *       되기 때문이다.
 *   <li><b>맥락 섹션</b> = 사용자가 담은 질의 대상·폼 입력·첨부 데이터·입력값. 질문에
 *       덧붙이지 않고 마지막 사용자 메시지 <b>앞의 별도 system 메시지</b>로 끼운다
 *       (사용자 결정 2026-07-24 — 질문과 맥락의 분리). 질문 텍스트가 오염되지 않아
 *       후속 추천이 원 질문만 보고, 첨부가 지시가 아니라 맥락임이 역할로 드러난다.
 * </ul>
 *
 * <p>섹션 머리표({@code SECTION_*})는 프롬프트 문장이 바뀌어도 유지되는 <b>표식</b>이다
 * — 목 LLM 과 테스트가 문장이 아니라 이 상수에 붙는다.
 */
public final class ChatPrompt {
    private ChatPrompt() {
    }

    /** 사용자가 질의 대상 트레이에 담은 설비·분석. */
    public static final String SECTION_SCOPE = "[질의 대상 — 사용자가 담은 것]";

    /** 붙여넣어 첨부된 데이터(스키마 카탈로그 — 행은 임시 DB 로 간다). */
    public static final String SECTION_DATA = "[제공된 데이터 — 사용자 첨부]";

    /** 조회 절차가 어디까지 왔고 다음 한 걸음이 무엇인지({@link QueryProgress}). */
    public static final String SECTION_PROGRESS = "[조회 절차 진행 상황]";

    /** 입력 카드로 채워 되보낸 스칼라 값. */
    public static final String SECTION_INPUTS = "[제공된 입력 — 사용자 입력]";

    /**
     * 툴과 무관한 공통 규율 — 정체성, 지어내지 않기, 답변 서식.
     *
     * <p>서식을 못 박는 이유: FE(MessageBubble)가 답변을 Markdown(GFM)으로 렌더한다.
     * 명시하지 않으면 굵게/목록이 깨지고, raw HTML 은 sanitize 로 지워진다.
     */
    private static final String IDENTITY = String.join(" ",
            "당신은 반도체 설비 이상탐지(FDC) 분석 어시스턴트다.",
            "설비/챔버/센서 데이터가 필요하면 반드시 제공된 툴을 호출해 사실을 확인하고,",
            "조회하지 않은 값은 지어내지 않는다.",
            "센서 데이터 분석에는 설비·PARAM_INDEX·기간이 모두 필요하다 — 폼 입력이나 대화에서",
            "이 중 빠진 게 있으면 추측하지 말고 무엇을 더 입력해야 하는지 사용자에게 되물어라.",
            "답변은 한국어로 간결하게 작성하고, 서식은 Markdown(GFM)으로 한다 —",
            "강조는 **굵게**, 구조가 필요하면 제목(##)·목록(-)·인용(>)을 쓰되 원시 HTML 은 쓰지 않는다.",
            "조회한 데이터 표는 화면에 따로 표시되니 본문에 같은 표를 다시 그리지 말고 해석·요약에 집중한다.");

    /** 정체성 + 이번 요청에 실제로 붙은 툴들의 사용 규율. */
    public static String system(List<AgentTool> tools) {
        List<String> rules = tools.stream()
                .map(AgentTool::guidance)
                .filter(g -> g != null && !g.isBlank())
                .toList();
        if (rules.isEmpty()) {
            return IDENTITY;
        }
        StringBuilder sb = new StringBuilder(IDENTITY).append("\n\n[도구 사용 규칙]");
        for (String rule : rules) {
            sb.append("\n- ").append(rule);
        }
        return sb.toString();
    }

    /**
     * 한 요청의 메시지 목록 — 시스템 프롬프트 + 대화 이력, 그 사이 마지막 사용자
     * 메시지 바로 앞에 맥락 섹션. 사용자 메시지가 하나도 없으면 맥락을 끝에 붙인다.
     */
    public static List<LlmMessage> messages(
            String system, List<HistoryMessage> history, String contextSection) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.of(Role.SYSTEM, system));

        List<LlmMessage> hist = new ArrayList<>(history.stream()
                .map(m -> LlmMessage.of(
                        m.role() == Role.ASSISTANT ? Role.ASSISTANT : Role.USER,
                        m.content() != null ? m.content() : ""))
                .toList());
        if (contextSection != null) {
            int lastUser = -1;
            for (int i = hist.size() - 1; i >= 0; i--) {
                if (hist.get(i).role() == Role.USER) {
                    lastUser = i;
                    break;
                }
            }
            if (lastUser >= 0) {
                hist.add(lastUser, LlmMessage.of(Role.SYSTEM, contextSection));
            } else {
                hist.add(LlmMessage.of(Role.USER, contextSection));
            }
        }
        messages.addAll(hist);
        return messages;
    }

    /** 맥락 섹션들을 빈 줄로 이어 하나의 블록으로. 전부 비면 null(주입 안 함). */
    public static String contextSection(
            QueryScope scope, List<ChatDataSnapshot> snapshots,
            SnapshotDb snapshotDb, QueryProgress progress,
            Map<String, Map<String, String>> inputs) {
        return join(
                queryScope(scope),
                dataSnapshots(snapshots, snapshotDb),
                progress != null ? progress.promptSection() : null,
                providedInputs(inputs));
    }

    /** 비지 않은 블록만 빈 줄로 이어 붙인다. 전부 비면 null. */
    static String join(String... blocks) {
        List<String> present = new ArrayList<>();
        for (String b : blocks) {
            if (b != null && !b.isBlank()) {
                present.add(b);
            }
        }
        return present.isEmpty() ? null : String.join("\n\n", present);
    }

    /**
     * 사용자가 담은 질의 대상 — 이 질문이 무엇을 놓고 하는 질문인지. 담긴 게 없으면
     * null(주입 안 함): 스코프는 좁히는 장치이지 필수 관문이 아니라, 안 담았다고 답을
     * 막지 않는다.
     *
     * <p>설비 줄과 분석 줄이 한 목록에 섞이는 건 의도다 — 둘은 성격이 같고 넓이만
     * 다르다. 분석 줄에는 그 분석의 조회 키를 같이 적는다: 같은 스킬이 두 설비에
     * 걸렸을 때 어느 값이 어느 쪽 것인지는 그렇게만 구분된다.
     */
    static String queryScope(QueryScope scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add(SECTION_SCOPE);
        if (scope.equipments() != null) {
            for (String eq : scope.equipments()) {
                if (eq != null && !eq.isBlank()) {
                    lines.add("- 설비 " + eq.trim() + " (전체)");
                }
            }
        }
        if (scope.analyses() != null) {
            for (QueryScope.Analysis a : scope.analyses()) {
                if (a == null || a.equipment() == null || a.equipment().isBlank()) {
                    continue;
                }
                lines.add("- 설비 " + a.equipment().trim() + " · " + analysisLabel(a));
            }
        }
        if (lines.size() == 1) {
            return null;
        }
        lines.add("이 질문은 위 대상에 관한 것이다 — 담기지 않은 설비는 답의 근거로 쓰지 말라.");
        return String.join("\n", lines);
    }

    /** 분석 줄의 꼬리 — "측정 분포 (fdc_trace_reading; days=30)". */
    private static String analysisLabel(QueryScope.Analysis a) {
        String focus = a.focus() != null && !a.focus().isBlank() ? a.focus().trim() : a.skill();
        List<String> detail = new ArrayList<>();
        if (a.skill() != null && !a.skill().isBlank()) {
            detail.add(a.skill().trim());
        }
        if (a.inputs() != null) {
            for (Map.Entry<String, String> kv : a.inputs().entrySet()) {
                if (kv.getKey() != null && kv.getValue() != null && !kv.getValue().isBlank()) {
                    detail.add(kv.getKey() + "=" + kv.getValue().trim());
                }
            }
        }
        return detail.isEmpty()
                ? String.valueOf(focus)
                : focus + " (" + String.join("; ", detail) + ")";
    }

    /**
     * 붙여넣어 요청에 실린 스냅샷. 아무것도 없으면 null(주입 안 함).
     *
     * <p>Design B: 행 있는 스냅샷은 {@link SnapshotDb}(임시 SQLite)로 적재되므로 여기엔
     * <b>스키마만</b> 편다 — 행은 프롬프트에 붓지 않고 query_snapshot 툴로 조회하게 한다
     * (토큰 절약·대용량 정밀 조회).
     *
     * <p>행이 없는 항목은 <b>두 종류로 갈라 적는다</b>. 조회해서 0행임을 확인한 것은
     * <b>사실</b>이고("그 데이터는 없다"는 답의 근거다), 아직 안 온 것은 요청 대상이다.
     * 둘을 뭉쳐 "미첨부"로 적으면, 없음을 확인해 등록한 사용자에게 같은 요청이 다시
     * 나가거나 모델이 오지 않을 데이터를 기다린다.
     */
    static String dataSnapshots(List<ChatDataSnapshot> snapshots, SnapshotDb snapshotDb) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add(SECTION_DATA);
        if (snapshotDb != null && !snapshotDb.isEmpty()) {
            parts.add("아래 표는 조회용 임시 DB(SQLite)로 적재되어 있다. 값이 필요하면 "
                    + SnapshotQueryTool.NAME + " 툴에 SELECT 문을 주어 조회하라(행 데이터는 여기 없다).");
            parts.add(snapshotDb.schemaCatalog());
        }

        List<String> emptyResults = new ArrayList<>();
        List<String> catalogOnly = new ArrayList<>();
        for (ChatDataSnapshot s : snapshots) {
            if (s == null || s.hasRows()) {
                continue;
            }
            String line = "- " + snapshotLabel(s) + " (" + columnList(s) + ")";
            if (s.isEmptyResult()) {
                emptyResults.add(line + " — 조회 결과 0행.");
            } else {
                catalogOnly.add(line + " — 내용 미첨부(필요하면 사용자에게 요청).");
            }
        }
        if (!emptyResults.isEmpty()) {
            parts.add("조회했으나 결과가 없던 항목 — 데이터가 없음이 확인된 것이다"
                    + "(아직 안 온 것이 아니다. 다시 요청하지 말고 없다는 사실로 답하라):");
            parts.addAll(emptyResults);
        }
        if (!catalogOnly.isEmpty()) {
            parts.add("아직 내용이 안 온 항목:");
            parts.addAll(catalogOnly);
        }
        // 머리표만 남았으면(적재된 표도 카탈로그도 없음) 주입하지 않는다.
        return parts.size() > 1 ? String.join("\n", parts) : null;
    }

    private static String snapshotLabel(ChatDataSnapshot s) {
        if (s.label() != null && !s.label().isBlank()) {
            return s.label();
        }
        return s.queryKey() != null && !s.queryKey().isBlank() ? s.queryKey() : "(이름 없는 항목)";
    }

    private static String columnList(ChatDataSnapshot s) {
        return String.join(", ", s.columns() != null ? s.columns() : List.of());
    }

    /**
     * 입력 카드로 채워 되보낸 스칼라 값 — 어느 스킬의 무슨 값인지 + "이미 있으니 그
     * 스킬을 이어서 진행하라". 아무것도 없으면 null(주입 안 함).
     * {@link InputRequestTool} 의 재요청 억제와 짝이라, 여기 실린 값은 다시 카드로
     * 나가지 않는다.
     */
    static String providedInputs(Map<String, Map<String, String>> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add(SECTION_INPUTS);
        boolean any = false;
        for (Map.Entry<String, Map<String, String>> e : inputs.entrySet()) {
            String skill = e.getKey();
            Map<String, String> kv = e.getValue();
            if (skill == null || kv == null) {
                continue;
            }
            for (Map.Entry<String, String> kvE : kv.entrySet()) {
                if (kvE.getKey() == null || kvE.getValue() == null || kvE.getValue().isBlank()) {
                    continue;
                }
                lines.add("- " + skill + "." + kvE.getKey() + " = " + kvE.getValue().trim());
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        lines.add("이 값들은 이미 제공됐다 — 다시 " + InputRequestTool.NAME
                + " 하지 말고 해당 스킬을 그 값으로 이어서 진행하라.");
        return String.join("\n", lines);
    }
}

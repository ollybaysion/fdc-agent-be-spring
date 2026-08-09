package fdc.agent.screens;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.contract.Role;
import fdc.agent.contract.ScreenMap;
import fdc.agent.llm.LlmTypes.LlmClient;
import fdc.agent.llm.LlmTypes.LlmMessage;
import fdc.agent.llm.LlmTypes.LlmTurn;
import fdc.agent.util.Trace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 캡처 화면 분류(#63) — <b>LLM 직접 분류 + 사용자 최종 확정</b> 설계의 BE 코어.
 * vision 호출에 화면 카탈로그({@link ScreenMapSource})를 통째로 주고 후보를
 * 받는다. <b>닫힌 목록을 강제</b>한다 — 카탈로그에 없는 id 는 버린다(지어낸
 * 화면명 차단). 후보별 근거는 UI 로 나가지 않고 {@link Trace} 로만 남긴다
 * (디버깅용, UI 미노출은 설계 결정).
 *
 * <p>확정은 항상 사용자다(후보 클릭 / ④ 직접 선택 / ⑤ 목록에 없음) — 이 클래스는
 * 후보를 좁히기만 하고 최종 판단은 하지 않는다. 그래서 LLM 이 틀려도 후보 순서가
 * 이상한 데 그치고 조용한 오분류가 없다. 선택·적중률 통계는 이 클래스의 관심이
 * 아니다(#67 공통 기록 층으로 분리).
 */
public final class ScreenClassifier {

    /**
     * 분류 지시 상수 — {@code MockLlm} 이 문장이 아니라 이 머리표로 분기를
     * 감지한다({@link fdc.agent.msg.MessageJudge#FORMAT_INSTRUCTION} 과 같은
     * 규율: 프롬프트 문구를 고쳤다고 목이 깨지면 프롬프트를 못 고치게 된다).
     */
    public static final String CLASSIFY_INSTRUCTION = "[화면 분류]";

    /** 카드 행 1~3에 실릴 후보 상한 — UX 확정(시안 A)의 고정 수. */
    private static final int MAX_CANDIDATES = 3;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient llm;
    private final ScreenMapSource screenMapSource;

    public ScreenClassifier(LlmClient llm, ScreenMapSource screenMapSource) {
        this.llm = llm;
        this.screenMapSource = screenMapSource;
    }

    /** 분류 결과 — 후보(≤3, 카드 행 1~3) + ④ 직접 선택 브라우저 재료(program group-by). */
    public record Classification(List<Candidate> candidates, Map<String, List<ScreenMap>> byProgram) {
    }

    /** 카드 행 하나의 재료 — {@code name} + {@code program › menuPath} 한 줄. */
    public record Candidate(String id, String name, String menuLabel) {
    }

    public Classification classify(String imageDataUrl) {
        List<ScreenMap> catalog = screenMapSource.maps();
        Map<String, ScreenMap> byId = new LinkedHashMap<>();
        for (ScreenMap m : catalog) {
            byId.put(m.id(), m);
        }
        List<LlmMessage> prompt = List.of(
                LlmMessage.withImages(Role.USER, promptText(catalog), List.of(imageDataUrl)));

        List<Candidate> candidates = List.of();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Trace.emit("BE→LLM 화면 분류 요청 (툴 없음, 이미지 1장)",
                        Map.of("catalogSize", catalog.size()));
                LlmTurn turn = llm.next(prompt, List.of());
                if (!(turn instanceof LlmTurn.Final fin) || fin.content() == null) {
                    continue;
                }
                candidates = parse(fin.content(), byId);
                break;
            } catch (RuntimeException e) {
                Trace.raw("화면 분류 실패 (닫힌 목록 강제 — ④⑤ 로 폴백)", String.valueOf(e));
            }
        }
        return new Classification(candidates, groupByProgram(catalog));
    }

    private static String promptText(List<ScreenMap> catalog) {
        return CLASSIFY_INSTRUCTION + """
                 첨부된 캡처 이미지가 아래 화면 목록 중 무엇인지 판단하라.
                반드시 아래 형태의 JSON 하나만 출력한다(설명·마크다운 금지):
                {"candidates": [{"id": "<목록의 id>", "reason": "<판단 근거 한 줄>"}]}
                후보는 최대 3개, 목록에 있는 id 만 쓴다 — 확신이 없으면 후보를 줄여도 된다.

                화면 목록:
                """ + catalogLines(catalog);
    }

    private static String catalogLines(List<ScreenMap> catalog) {
        List<String> lines = new ArrayList<>();
        for (ScreenMap m : catalog) {
            StringBuilder line = new StringBuilder("- ").append(m.id()).append(": ").append(m.name());
            String menu = menuLabel(m);
            if (!menu.isBlank()) {
                line.append(" (").append(menu).append(')');
            }
            if (m.hints() != null && !m.hints().isEmpty()) {
                line.append(" — ").append(String.join("; ", m.hints()));
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    /**
     * 후보 id 를 카탈로그와 대조해 실재하는 것만 남긴다(<b>닫힌 목록 강제</b>) —
     * 근거는 반환값이 아니라 {@link Trace} 로만 남긴다.
     */
    private static List<Candidate> parse(String content, Map<String, ScreenMap> byId) {
        String body = stripFences(content);
        try {
            Map<?, ?> root = JSON.readValue(body, Map.class);
            if (!(root.get("candidates") instanceof List<?> items)) {
                return List.of();
            }
            List<Candidate> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Object item : items) {
                if (out.size() >= MAX_CANDIDATES) {
                    break;
                }
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String id = asText(m.get("id"));
                ScreenMap screen = id != null ? byId.get(id) : null;
                if (screen == null) {
                    Trace.raw("화면 분류 후보 버림 (카탈로그에 없는 id)",
                            id + " — 근거: " + asText(m.get("reason")));
                    continue;
                }
                if (!seen.add(screen.id())) {
                    continue; // 같은 화면을 중복 지목 — 카드 한 칸만 채운다
                }
                Trace.raw("화면 분류 후보 채택", screen.id() + " — 근거: " + asText(m.get("reason")));
                out.add(new Candidate(screen.id(), screen.name(), menuLabel(screen)));
            }
            return out;
        } catch (Exception e) {
            Trace.raw("화면 분류 응답 파싱 실패 (④⑤ 로 폴백)", content + " — " + e);
            return List.of();
        }
    }

    /** {@code program › menuPath 요소들} — 둘 다 없으면 빈 문자열. */
    static String menuLabel(ScreenMap m) {
        List<String> parts = new ArrayList<>();
        if (m.program() != null && !m.program().isBlank()) {
            parts.add(m.program());
        }
        if (m.menuPath() != null) {
            parts.addAll(m.menuPath());
        }
        return String.join(" › ", parts);
    }

    /**
     * ④ 직접 선택 브라우저 재료 — program 으로 묶는다. 표기가 다르면(공백·대소문자
     * 차이 등) 그룹이 갈라진다 — 정규화하지 않는다(현상 고정, 저작 규율로 미룬다).
     */
    static Map<String, List<ScreenMap>> groupByProgram(List<ScreenMap> catalog) {
        Map<String, List<ScreenMap>> out = new LinkedHashMap<>();
        for (ScreenMap m : catalog) {
            String program = m.program() != null ? m.program() : "미분류";
            out.computeIfAbsent(program, p -> new ArrayList<>()).add(m);
        }
        return out;
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

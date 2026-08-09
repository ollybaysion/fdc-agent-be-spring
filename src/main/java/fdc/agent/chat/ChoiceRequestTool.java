package fdc.agent.chat;

import fdc.agent.contract.ChoiceOption;
import fdc.agent.contract.ChoiceRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code choice_request} — 빠진 값의 후보를 2~10개로 좁혔을 때 사용자에게 <b>선택지
 * 카드</b>로 묻는 수집 툴. {@link InputRequestTool} 과 같은 자리에 있고 같은 규율을
 * 따른다: 실행하지 않고 모아 두었다가 done 페이로드의 {@code choiceRequests} → FE
 * 선택 카드로 나간다.
 *
 * <p>회신 채널은 카드 클릭뿐이다({@code request_input} 의 {@code inputs} 회신과
 * 다르다) — 사용자가 고르면 {@code "선택 — {label}"} 형태의 평범한 user 메시지로
 * 대화에 돌아오므로, 이 툴은 그 회신을 억제할 상태를 프롬프트에 따로 주입하지 않고
 * 대화 이력의 흐름에 맡긴다. 인스턴스는 <b>턴 내 중복</b>(같은 question 재요청)만 막는다.
 */
public final class ChoiceRequestTool implements AgentTool {

    public static final String NAME = "choice_request";

    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 10;

    private final Set<String> askedQuestions = new LinkedHashSet<>();
    private final List<ChoiceRequest> collected = new ArrayList<>();

    /** 이번 응답에서 모인 선택 요청(턴 내 중복은 빠져 있다). */
    public List<ChoiceRequest> collected() {
        return List.copyOf(collected);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "빠진 값의 후보를 2~10개로 좁혔을 때 선택지를 버튼 카드로 제시한다 — 프로즈로 "
                + "되묻지 않는다. 사용자의 클릭이 다음 질문에 \"선택 — …\" 로 실려 온다.";
    }

    @Override
    public String guidance() {
        return "빠진 값의 후보를 2~10개로 좁힐 수 있으면 프로즈로 길게 묻지 말고 " + NAME
                + " 로 선택지를 제시하라. 여러 개 선택이 자연스러우면 multiSelect=true. "
                + "후보를 모르는 자유 입력 값은 " + InputRequestTool.NAME + " 을 쓴다. "
                + "사용자가 이미 답한(\"선택 — …\") 질문은 다시 묻지 않는다.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("question", ToolArgs.string("사용자에게 묻는 질문 문장."));
        Map<String, Object> optionProps = new LinkedHashMap<>();
        optionProps.put("label", ToolArgs.string("선택지 문구 — 카드에 표시되고 회신에 그대로 실린다."));
        optionProps.put("description", ToolArgs.string("선택지 보충 설명(선택)."));
        props.put("options", ToolArgs.objectArray("선택지 2~10개.", optionProps, List.of("label")));
        props.put("multiSelect", ToolArgs.bool("여러 개를 고를 수 있으면 true(기본 false)."));
        return ToolArgs.schema(props, List.of("question", "options"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String question = ToolArgs.text(args, "question");
        List<ChoiceOption> options = normalizeOptions(args != null ? args.get("options") : null);
        if (question == null || options.size() < MIN_OPTIONS) {
            return ToolResult.of("선택 요청이 형식에 맞지 않아 등록하지 못했습니다"
                    + "(question·options 2개 이상 필요).");
        }
        if (options.size() > MAX_OPTIONS) {
            options = options.subList(0, MAX_OPTIONS);
        }
        if (!askedQuestions.add(question)) {
            return ToolResult.of("이미 제시한 질문입니다: " + question + " — 사용자의 회신을 기다리세요.");
        }
        collected.add(new ChoiceRequest(question, options, ToolArgs.flag(args, "multiSelect")));
        return ToolResult.of("선택지를 제시했습니다: " + question
                + " — 사용자가 카드에서 고르면 그 값으로 이어서 진행합니다. 답을 지어내지 말고 짧게 마무리하십시오.");
    }

    /** 문자열 아이템은 {label} 로 정규화(관용 수용), label 없는 항목은 버린다. */
    private static List<ChoiceOption> normalizeOptions(Object raw) {
        List<ChoiceOption> out = new ArrayList<>();
        if (!(raw instanceof List<?> items)) {
            return out;
        }
        for (Object item : items) {
            if (item instanceof String s) {
                String label = s.trim();
                if (!label.isEmpty()) {
                    out.add(new ChoiceOption(label, null));
                }
            } else if (item instanceof Map<?, ?> m) {
                Object labelRaw = m.get("label");
                String label = labelRaw != null ? String.valueOf(labelRaw).trim() : "";
                if (label.isEmpty()) {
                    continue;
                }
                Object descRaw = m.get("description");
                String description = descRaw != null ? String.valueOf(descRaw).trim() : "";
                out.add(new ChoiceOption(label, description.isEmpty() ? null : description));
            }
        }
        return out;
    }
}

package fdc.agent.chat;

import fdc.agent.contract.InputRequest;
import fdc.agent.contract.QueryScope;
import fdc.agent.skills.SkillSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code request_input} — 스킬에 필요한 스칼라 값 하나를 <b>사용자에게 입력 요청</b>하는
 * 수집 툴. {@link RetrieveDataTool} 과 같은 자리에 있고 같은 규율을 따른다: 실행하지 않고
 * 모아 두었다가 done 페이로드의 {@code inputRequests} → FE 입력 카드로 나간다.
 *
 * <p>이미 값이 있는 (skill, key) 는 카드로 내보내지 않는다. 이미 있는 값은 두 곳에서
 * 온다 — 사용자가 카드에 채워 되보낸 {@code inputs}, 그리고 진입 폼에서 사람이 정해
 * 트레이에 담아 온 분석의 조회 키({@link QueryScope}). 둘 중 하나만 봤다가는 사람이
 * 이미 답한 값을 다시 묻게 된다.
 */
public final class InputRequestTool implements AgentTool {

    public static final String NAME = "request_input";

    /** 억제 키 — (스킬, 인자) 쌍. 회신 네임스페이스와 같은 규칙. */
    private record InputKey(String skill, String key) {
    }

    private final Set<InputKey> provided = new LinkedHashSet<>();
    private final Set<InputKey> requested = new LinkedHashSet<>();
    private final List<InputRequest> collected = new ArrayList<>();
    /** spec 이 선언한 인자별 입력 위젯 신호 — (스킬, 인자) → datetime|date. */
    private final Map<InputKey, String> inputTypes = new LinkedHashMap<>();

    public InputRequestTool(
            Map<String, Map<String, String>> inputs, QueryScope scope, List<SkillSpec> specs) {
        addProvidedInputs(inputs);
        addScopeInputs(scope);
        addSpecInputTypes(specs);
    }

    /**
     * 입력 위젯 신호는 LLM 인자가 아니라 spec 소관 — (skill, key) 로 여기서 결정론으로
     * 찾는다. LLM 은 스킬을 툴 이름(언더스코어)으로 부르므로 두 표기 다 등록한다.
     */
    private void addSpecInputTypes(List<SkillSpec> specs) {
        if (specs == null) {
            return;
        }
        for (SkillSpec spec : specs) {
            if (spec == null || spec.name() == null || spec.inputs() == null) {
                continue;
            }
            for (SkillSpec.SkillInput in : spec.inputs()) {
                if (in.type() == null) {
                    continue;
                }
                inputTypes.put(new InputKey(spec.name(), in.name()), in.type());
                inputTypes.put(new InputKey(spec.name().replace("-", "_"), in.name()), in.type());
            }
        }
    }

    /** 사용자가 입력 카드로 채워 되보낸 값. */
    private void addProvidedInputs(Map<String, Map<String, String>> inputs) {
        if (inputs == null) {
            return;
        }
        for (Map.Entry<String, Map<String, String>> e : inputs.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            for (Map.Entry<String, String> kv : e.getValue().entrySet()) {
                addIfFilled(e.getKey(), kv.getKey(), kv.getValue());
            }
        }
    }

    /** 트레이에 담긴 분석이 들고 온 조회 키 — 진입 폼에서 사람이 이미 정한 값이다. */
    private void addScopeInputs(QueryScope scope) {
        if (scope == null || scope.analyses() == null) {
            return;
        }
        for (QueryScope.Analysis a : scope.analyses()) {
            if (a == null || a.skill() == null || a.inputs() == null) {
                continue;
            }
            for (Map.Entry<String, String> kv : a.inputs().entrySet()) {
                addIfFilled(a.skill(), kv.getKey(), kv.getValue());
            }
        }
    }

    private void addIfFilled(String skill, String key, String value) {
        if (key != null && value != null && !value.isBlank()) {
            provided.add(new InputKey(skill, key));
        }
    }

    /** 이번 응답에서 모인 입력 요청(억제된 것은 빠져 있다). */
    public List<InputRequest> collected() {
        return List.copyOf(collected);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "스킬에 필요한 값 하나를 사용자에게 입력 요청한다 — 조회는 하지 않는다. 요청은 화면에 "
                + "입력 카드로 뜨고, 채워진 값은 다음 질문에 실려 온다.";
    }

    @Override
    public String guidance() {
        return "스킬에 필요한 값(설비·PARAM_INDEX 등)이 폼·대화에 없으면, 프로즈로 길게 되묻지 말고 " + NAME
                + " 툴로 그 값 하나를 입력 카드로 요청하라 — 진행하려는 스킬(툴) 이름을 skill 로, 인자 이름을 "
                + "key 로 준다. 이미 [제공된 입력]으로 받은 값은 다시 요청하지 말고 그 스킬을 그 값으로 이어서 진행한다.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("skill", ToolArgs.string(
                "이 값이 필요한 스킬(툴) 이름 — 지금 진행하려는 그 스킬. 회신이 이 스킬로 묶인다."));
        props.put("key", ToolArgs.string(
                "필요한 스킬 인자 이름(예: param_index). 사용자가 채우면 이 이름으로 회신된다."));
        props.put("label", ToolArgs.string(
                "사람이 읽는 입력 이름 — 화면 카드에 표시된다(예: PARAM_INDEX)."));
        props.put("description", ToolArgs.string("무슨 값을 넣어야 하는지 짧은 안내(선택)."));
        return ToolArgs.schema(props, List.of("skill", "key", "label"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String skill = ToolArgs.text(args, "skill");
        String key = ToolArgs.text(args, "key");
        String label = ToolArgs.text(args, "label");
        if (skill == null || key == null || label == null) {
            return ToolResult.of("입력 요청이 형식에 맞지 않아 등록하지 못했습니다(skill·key·label 필요).");
        }
        InputKey dedup = new InputKey(skill, key);
        if (provided.contains(dedup) || !requested.add(dedup)) {
            return ToolResult.of("이미 제공되었거나 요청된 입력입니다: " + label + " — 그 값으로 이어서 진행하라.");
        }
        collected.add(new InputRequest(skill, key, label,
                ToolArgs.text(args, "description"), inputTypes.get(dedup)));
        return ToolResult.of("입력 요청을 등록했습니다: " + label
                + ". 데이터 패널의 입력 카드에 값을 넣어 주시면 그 값으로 이어서 분석합니다. 없는 값은 지어내지 않습니다.");
    }
}

package fdc.agent.chat;

import fdc.agent.contract.ChatDataSnapshot;
import fdc.agent.contract.DataRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code request_data} — 조회 툴로 닿지 않는 데이터를 <b>사용자에게 조달 요청</b>하는
 * 수집 툴. 실행하지 않고 요청을 모아 두었다가, 루프가 끝나면 done 페이로드의
 * {@code dataRequests} → FE 요청 카드로 나간다.
 *
 * <p><b>억제가 왕복을 끝낸다</b>: 이미 받은 스냅샷과 같은 queryKey 이거나 이번 응답에서
 * 이미 요청한 키면 카드로 내보내지 않는다. LLM 의 판단에 기대지 않고 여기서 결정론적으로
 * 확정하므로, 모델이 헷갈려도 같은 데이터를 무한히 다시 요청하지 못한다.
 *
 * <p>인스턴스는 요청 단위다 — 수집 상태(이미 요청한 키, 모은 요청)를 자기가 들고 있다.
 */
public final class DataRequestTool implements AgentTool {

    public static final String NAME = "request_data";

    /** 이미 제공된 스냅샷의 queryKey — 이 키는 다시 요청하지 않는다. */
    private final Set<String> supplied = new LinkedHashSet<>();

    /** 이번 응답에서 이미 요청한 키 — 한 응답 안의 중복도 막는다. */
    private final Set<String> requested = new LinkedHashSet<>();

    private final List<DataRequest> collected = new ArrayList<>();

    public DataRequestTool(List<ChatDataSnapshot> snapshots) {
        if (snapshots == null) {
            return;
        }
        for (ChatDataSnapshot s : snapshots) {
            if (s != null && s.queryKey() != null && !s.queryKey().isBlank()) {
                supplied.add(s.queryKey());
            }
        }
    }

    /** 이번 응답에서 모인 조달 요청(억제된 것은 빠져 있다). */
    public List<DataRequest> collected() {
        return List.copyOf(collected);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "필요한 데이터를 사용자에게 조달 요청한다 — 조회는 하지 않는다. 요청은 화면에 카드로 뜨고, "
                + "사용자가 SQL 을 실행해 결과를 붙여넣으면 다음 질문에 실려 온다.";
    }

    @Override
    public String guidance() {
        return "DB 에 직접 조회할 수 없어 필요한 데이터를 얻지 못하면, 값을 지어내지 말고 " + NAME
                + " 툴로 사용자에게 조달을 요청하라 — 안정적인 snake_case queryKey 와, 가능하면 실행할 SQL 을 "
                + "함께 준다. 이미 [제공된 데이터]로 받은 것(같은 queryKey)은 다시 요청하지 않는다.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("queryKey", ToolArgs.string(
                "이 데이터를 충족할 스냅샷의 안정 키(snake_case). 사용자가 붙여넣으면 이 키로 등록돼 "
                        + "다음 요청에서 충족 여부를 판정한다."));
        props.put("label", ToolArgs.string("사람이 읽는 설명 — 무슨 데이터가 왜 필요한지."));
        props.put("sql", ToolArgs.string("사용자가 실행할 SQL(가능하면). 화면에 복사 버튼과 함께 표시된다."));
        props.put("columns", ToolArgs.stringArray(
                "기대 컬럼(선택) — 사용자가 맞는 결과를 붙여넣었는지 가늠용."));
        return ToolArgs.schema(props, List.of("queryKey", "label"));
    }

    @Override
    public ToolResult run(Map<String, Object> args) {
        String queryKey = ToolArgs.text(args, "queryKey");
        String label = ToolArgs.text(args, "label");
        if (queryKey == null || label == null) {
            return ToolResult.of("데이터 요청이 형식에 맞지 않아 등록하지 못했습니다(queryKey·label 필요).");
        }
        if (supplied.contains(queryKey) || !requested.add(queryKey)) {
            return ToolResult.of("이미 제공되었거나 요청된 데이터입니다: " + label + " — 그대로 분석을 이어가라.");
        }
        collected.add(new DataRequest(
                queryKey, label, ToolArgs.text(args, "sql"), ToolArgs.strings(args, "columns")));
        return ToolResult.of("데이터 요청을 등록했습니다: " + label
                + ". 데이터 패널 카드의 SQL 을 실행해 결과를 붙여넣어 등록하고, 채팅에 \"등록 완료\"라고"
                + " 알려주시면 그 데이터로 이어서 분석합니다. 없는 값은 지어내지 않습니다.");
    }
}

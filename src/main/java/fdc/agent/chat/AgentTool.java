package fdc.agent.chat;

import fdc.agent.contract.ChatTable;
import java.util.List;
import java.util.Map;

/**
 * 에이전트 툴 — LLM 이 보는 정의(name·description·parameters)와 호출됐을 때 할 일
 * ({@link #run})을 한 덩어리로 묶는다. 이 모양이 곧 도메인 스킬 spec 이 컴파일되면
 * 나올 결과물 — {@link fdc.agent.skills.SkillLoader} 가 spec.json 을 읽어 이 형태로
 * 만들면 에이전트/라우트 무수정으로 새 스킬이 붙는다.
 *
 * <p>툴은 두 갈래다. <b>조회 툴</b>은 실제로 데이터를 가져와 요약과 표를 돌려주고
 * (스킬 컴파일 결과 · {@link SnapshotQueryTool}), <b>수집 툴</b>은 실행하지 않고
 * "이게 필요하다"를 모아 done 페이로드로 내보낸다({@link RetrieveDataTool} ·
 * {@link InputRequestTool}). 루프는 둘을 구분하지 않는다 — 어느 쪽이든 이름으로 찾아
 * run 을 부르고 요약을 되먹인다. 수집한 것은 툴 자신이 들고 있다가 루프가 끝날 때
 * 꺼내 간다(수집 툴 인스턴스는 요청 단위).
 *
 * <p>{@link #guidance()} 는 이 툴을 언제·어떻게 쓰라는 규율로, 시스템 프롬프트의 툴
 * 사용 규칙 절에 실린다({@link ChatPrompt#system}). 규율이 툴과 같은 자리에 있어야
 * 툴이 안 붙은 요청에선 규율도 같이 빠진다 — 없는 툴을 쓰라는 지시가 나가지 않는다.
 */
public interface AgentTool {

    String name();

    /** LLM 이 읽는 툴 설명 — 무엇을 하는 툴인가(언제 쓰는지는 {@link #guidance()}). */
    String description();

    /** OpenAI function JSON Schema. */
    Map<String, Object> parameters();

    /** 시스템 프롬프트에 실을 사용 규율. 없으면 null(설명만으로 충분한 툴). */
    default String guidance() {
        return null;
    }

    ToolResult run(Map<String, Object> args);

    /** LLM 에 되먹일 요약(자연어) + done 페이로드에 실을 표(옵션, null 허용). */
    record ToolResult(String summary, List<ChatTable> tables) {
        public static ToolResult of(String summary) {
            return new ToolResult(summary, null);
        }
    }

    @FunctionalInterface
    interface ToolFn {
        ToolResult run(Map<String, Object> args);
    }

    /** 상태 없는 조회 툴 — spec 컴파일 결과처럼 정의와 함수만 있으면 되는 경우. */
    static AgentTool of(
            String name, String description, Map<String, Object> parameters, ToolFn fn) {
        return of(name, description, null, parameters, fn);
    }

    static AgentTool of(
            String name, String description, String guidance,
            Map<String, Object> parameters, ToolFn fn) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> parameters() {
                return parameters;
            }

            @Override
            public String guidance() {
                return guidance;
            }

            @Override
            public ToolResult run(Map<String, Object> args) {
                return fn.run(args);
            }
        };
    }
}

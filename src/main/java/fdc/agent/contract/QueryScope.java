package fdc.agent.contract;

import java.util.List;
import java.util.Map;

/**
 * 이 질문이 <b>무엇을 놓고 하는 질문인지</b> — 사용자가 담은 대상.
 *
 * <p>화면의 정체는 "이 설비에 관해 자유롭게 질의하세요"다. 그런데 어느 설비인지는
 * 사람만 아니까, 사용자가 설비 카드를 질의 대상 트레이에 담는다. 담긴 것이 곧 이
 * 질문의 범위다 — 담기지 않은 설비는 답의 근거가 아니다.
 *
 * <p>담는 단위는 둘인데 <b>성격은 같고 넓이만 다르다</b>: 설비를 담으면 그 설비
 * 전체가, 분석 카드를 담으면 그 설비의 그 분석만 대상이 된다. 그래서 하나의 스코프
 * 안에 둘이 섞여 산다 — "CVD-01 은 통째로, CVD-02 는 측정 분포만".
 *
 * <p>{@code analyses[].inputs} 는 진입 폼에서 사람이 정한 조회 키다(어느 센서를,
 * 며칠치를). 채팅이 되물어 채운 {@code inputs}(스킬로만 네임스페이스된 평면 맵)와
 * 달리 <b>분석 카드별로</b> 갈라져 있다 — 같은 스킬을 두 설비에 걸면 값이 서로
 * 달라야 하는데, 스킬 한 칸으로는 그걸 담지 못하기 때문이다.
 *
 * <p>필드가 통째로 없으면(=null) 담긴 것이 없다는 뜻이고, 그때 답은 지금까지처럼
 * 대화 맥락 전체를 본다 — 스코프는 좁히는 장치이지 필수 관문이 아니다.
 */
public record QueryScope(List<String> equipments, List<Analysis> analyses) {

    /** 담긴 분석 카드 하나 = 그 설비를 그 스킬로 보는 한 벌. */
    public record Analysis(
            String id,
            String equipment,
            String skill,
            String focus,
            Map<String, String> inputs) {
    }

    /** 담긴 것이 하나도 없는가 — null 필드와 빈 목록을 같게 본다. */
    public boolean isEmpty() {
        return (equipments == null || equipments.isEmpty())
                && (analyses == null || analyses.isEmpty());
    }
}

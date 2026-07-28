package fdc.agent.lines;

import java.util.List;

/**
 * 라인 목록 출처 seam — 스킬의 {@code SkillSource} 와 같은 자리다.
 *
 * <p>라인은 사람이 타이핑할 값이 아니라 <b>정해진 목록에서 고르는 값</b>이라, 그
 * 목록에는 진실원이 있어야 한다. 진실원은 akg 의 {@code fab-line} 문서 집합이고,
 * 미설정이면 빈 목록이다 — <b>BE 가 라인 코드를 지어내지 않는다.</b> 빈 목록을 받은
 * 화면은 라인 칸을 아예 그리지 않는다(고를 수 없는 칸을 세우는 것보다 낫다).
 */
@FunctionalInterface
public interface LineSource {

    /** 고를 수 있는 라인 코드들. 순서는 그대로 화면의 드롭다운 순서다. */
    List<String> codes();
}

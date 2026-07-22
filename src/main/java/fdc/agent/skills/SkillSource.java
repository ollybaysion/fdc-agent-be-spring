package fdc.agent.skills;

import java.util.List;

/**
 * 스킬 spec 의 출처 seam — classpath 번들({@link SkillRegistry#bundledSpecs})
 * 또는 akg 지식 허브({@link AkgSkillSource}). ChatAgent 는 요청마다 이걸
 * 물어 툴을 컴파일하므로, 출처가 갱신되면 재배포 없이 다음 요청부터 반영된다.
 */
@FunctionalInterface
public interface SkillSource {

    /** 현재 유효한 스킬 spec 목록. 반환된 spec 은 배선(binds)까지 완결이어야 한다. */
    List<SkillSpec> specs();
}

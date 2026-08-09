package fdc.agent.screens;

import fdc.agent.contract.ScreenMap;
import java.util.List;

/**
 * 화면 카탈로그 출처 seam — classpath 번들({@link BundledScreenMapSource}) 또는
 * akg 지식 허브({@link AkgScreenMapSource}). {@link ScreenClassifier} 는 분류
 * 호출마다 이걸 물어 카탈로그를 만드므로, 출처가 갱신되면 재배포 없이 다음
 * 호출부터 반영된다.
 */
@FunctionalInterface
public interface ScreenMapSource {

    /** 현재 유효한 화면 목록. */
    List<ScreenMap> maps();
}

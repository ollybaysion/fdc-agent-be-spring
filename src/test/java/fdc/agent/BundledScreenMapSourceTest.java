package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import fdc.agent.contract.ScreenMap;
import fdc.agent.screens.BundledScreenMapSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * classpath 번들 화면 카탈로그(#63). {@code src/test/resources/screen-maps/broken.json}
 * (id 없는 나쁜 문서)이 테스트 classpath 에 함께 실리는데, 그래도 데모 3장이 그대로
 * 뜨는지로 격리를 확인한다 — 격리가 안 됐다면 정적 초기화 자체가 예외로 죽는다.
 */
class BundledScreenMapSourceTest {

    @Test
    void 데모_3장을_로드하고_깨진_문서_1장은_나머지를_못_죽인다() {
        List<ScreenMap> maps = new BundledScreenMapSource().maps();
        List<String> ids = maps.stream().map(ScreenMap::id).toList();
        assertThat(ids).contains("fdc-monitor-history", "fdc-monitor-trend", "spc-control-chart");
    }
}

package fdc.agent.screens;

import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.contract.ScreenMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * classpath {@code screen-maps/} 의 화면 문서를 스캔하는 {@link ScreenMapSource}
 * (akg 미설정·불가침 시의 폴백). 스킬 번들({@code SkillRegistry})과 같은 관용 —
 * 새 화면 추가 = 그 폴더에 문서 하나를 떨구면 끝(코드 편집 0).
 *
 * <p>스킬 번들과 다른 점 하나: 문서 하나가 깨져도 <b>그 문서만 빠지고 나머지는
 * 뜬다</b>. 화면 카탈로그는 저작 문턱이 스킬보다 낮아(#63 설계) 부팅을 막을
 * 이유가 없다 — {@link AkgScreenMapSource} 의 fail-open 규율을 번들에도 맞춘다.
 */
public final class BundledScreenMapSource implements ScreenMapSource {

    private static final Logger log = LoggerFactory.getLogger(BundledScreenMapSource.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ScreenMap> LOADED = loadAll();

    private static List<ScreenMap> loadAll() {
        Resource[] docs;
        try {
            // classpath*: (단일 classpath: 아님) — 리소스 루트가 여럿이면(멀티 모듈·
            // 테스트 리소스 등) 전부 병합해 스캔한다. 단일 루트만 보면 다른 루트의
            // 동명 폴더에 가려 이 루트의 문서가 통째로 안 보이는 사고가 난다.
            docs = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:screen-maps/*.json");
        } catch (IOException e) {
            throw new UncheckedIOException("화면 카탈로그 폴더 스캔 실패", e);
        }
        List<ScreenMap> out = new ArrayList<>();
        for (Resource res : Arrays.stream(docs)
                .sorted(Comparator.comparing(Resource::getFilename)).toList()) {
            try (var in = res.getInputStream()) {
                out.add(JSON.readValue(in, ScreenMap.class));
            } catch (Exception e) {
                log.warn("화면 문서 {} 수용 불가 — 제외: {}", res.getFilename(), e.toString());
            }
        }
        return List.copyOf(out);
    }

    @Override
    public List<ScreenMap> maps() {
        return LOADED;
    }
}

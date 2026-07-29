package fdc.agent.api;

import fdc.agent.lines.LineSource;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/fdc/v1/lines — 설비가 속할 수 있는 라인 목록.
 *
 * <p>FE 의 설비 등록 폼이 드롭다운으로 쓴다. 진실원은 akg 의 {@code fab-line} 문서
 * 집합이고, 이 라우트는 그것을 브라우저가 닿을 수 있는 자리로 옮겨 놓을 뿐이다 —
 * 브라우저가 akg 주소·토큰을 알 이유가 없다(스킬 목록과 같은 어법).
 *
 * <p>못 받으면 빈 배열이다. 200 으로 빈 목록을 주는 것이 500 보다 낫다: 라인은
 * 필수가 아니라 폼이 그 칸만 안 그리면 되고, 라인 하나 때문에 설비 등록 자체가
 * 막히면 안 된다.
 */
@RestController
public class LinesController {

    private final LineSource lines;

    public LinesController(LineSource lines) {
        this.lines = lines;
    }

    @GetMapping("/api/fdc/v1/lines")
    public Map<String, List<String>> lines() {
        return Map.of("lines", lines.codes());
    }
}

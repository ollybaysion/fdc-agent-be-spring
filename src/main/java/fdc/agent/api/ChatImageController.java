package fdc.agent.api;

import fdc.agent.config.ApiException;
import fdc.agent.contract.ScreenClassification;
import fdc.agent.contract.ScreenClassification.BrowseGroup;
import fdc.agent.contract.ScreenClassification.ScreenOption;
import fdc.agent.contract.ScreenMap;
import fdc.agent.screens.ScreenClassifier;
import fdc.agent.util.Trace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/fdc/v1/chat/image — 캡처 화면 분류 인렛(#63). 붙여넣은 캡처 한 장을
 * 받아 {@link ScreenClassifier} 로 후보를 좁히고, 카드가 그릴 형태로 투영해 답한다.
 *
 * <p><b>동기 JSON 이다</b> — SSE 가 아니다. {@code /chat}·{@code /chat/data} 는 답을
 * 글자로 흘려야 해서 스트리밍이지만 이쪽은 LLM 1회로 끝나고 흘릴 산문이 없다.
 * 그래도 {@code /chat/*} 네임스페이스에 있는 것은 이것이 대화의 일부이기 때문이다 —
 * 캡처를 붙여넣는 것은 별도 업로드 채널이 아니라 데이터를 대는 한 가지 방식이다.
 *
 * <p>확정은 하지 않는다. 이 인렛은 <b>후보를 좁히고 전체 목록을 함께 줄 뿐</b>이고,
 * 어느 화면인지는 사람이 카드에서 누른다(demo-fe#189). 그래서 분류가 틀려도 조용한
 * 오분류가 아니라 후보 순서가 이상한 것에 그친다.
 */
@RestController
public class ChatImageController {

    private static final Logger log = LoggerFactory.getLogger(ChatImageController.class);

    /** 받아들이는 그림 형식 — FE {@code IMAGE_MIME} 과 같은 목록(demo-fe clipboard-ingest). */
    private static final Set<String> IMAGE_MIME =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    /** 그림 한 장의 상한 — FE {@code IMAGE_MAX_BYTES} 와 같은 기준. base64 가 아니라 <b>디코드된</b> 바이트. */
    private static final int IMAGE_MAX_BYTES = 5 * 1024 * 1024;

    private static final String BASE64_MARK = ";base64,";

    private final ScreenClassifier classifier;

    public ChatImageController(ScreenClassifier classifier) {
        this.classifier = classifier;
    }

    /** 요청 본문 — data URL 하나. 캡처 외의 맥락(대화 이력 등)은 분류에 안 쓴다. */
    public record ImageBody(String image) {
    }

    @PostMapping("/api/fdc/v1/chat/image")
    public ScreenClassification chatImage(@RequestBody(required = false) ImageBody body) {
        String image = body != null ? body.image() : null;
        validate(image);

        Trace.emit("FE→BE 요청 POST /api/fdc/v1/chat/image",
                Map.of("imageChars", image.length()));

        ScreenClassifier.Classification result;
        try {
            result = classifier.classify(image);
        } catch (RuntimeException e) {
            // 계약이 "항상 200" 이다 — 카탈로그 소스가 넘어져도 화면은 ④⑤ 로 진행할 수
            // 있어야 하기 때문이다. 다만 조용히 삼키지는 않는다: 후보가 빈 응답은
            // 정상적인 분류 실패와 구분이 안 되므로 여기서 크게 남긴다.
            log.error("chat/image classify error — 빈 응답으로 강등", e);
            return new ScreenClassification(List.of(), List.of());
        }

        ScreenClassification out = project(result);
        Trace.emit("BE→FE 응답 (chat/image)", Map.of(
                "candidates", out.candidates().size(),
                "browseGroups", out.browse().size()));
        return out;
    }

    /**
     * data URL 검증 — 형식·형태·크기. 여기서 걸러야 vision 호출에 쓰레기가 실려
     * 나가지 않는다. 문구를 코드로 나누는 이유는 FE 가 이유별로 다르게 말하기
     * 때문이다(형식이 문제인지 크기가 문제인지는 사람이 할 일이 다르다).
     */
    private static void validate(String image) {
        if (image == null || image.isBlank()) {
            throw new ApiException(400, "image_required", "image(data URL)가 필요합니다.");
        }
        if (!image.startsWith("data:")) {
            throw new ApiException(400, "image_invalid", "data URL 형식이 아닙니다.");
        }
        int mark = image.indexOf(BASE64_MARK);
        if (mark < 0) {
            throw new ApiException(400, "image_invalid", "base64 data URL 이 아닙니다.");
        }
        String mime = image.substring("data:".length(), mark);
        if (!IMAGE_MIME.contains(mime)) {
            throw new ApiException(400, "image_unsupported",
                    "받을 수 없는 그림 형식입니다: " + (mime.isBlank() ? "(없음)" : mime));
        }
        int bytes = decodedLength(image.substring(mark + BASE64_MARK.length()));
        if (bytes > IMAGE_MAX_BYTES) {
            throw new ApiException(400, "image_too_large",
                    "그림이 너무 큽니다 (" + bytes / 1024 / 1024 + "MB / 최대 "
                            + IMAGE_MAX_BYTES / 1024 / 1024 + "MB).");
        }
    }

    /**
     * base64 길이에서 디코드 후 바이트 수 — 실제로 디코드하지 않는다(5MB 를 넘겼는지
     * 보려고 5MB 를 만들 이유가 없다). 4 글자가 3 바이트고 끝의 {@code =} 는 채움이다.
     */
    private static int decodedLength(String base64) {
        int len = base64.length();
        if (len == 0) {
            return 0;
        }
        int padding = 0;
        for (int i = len - 1; i >= 0 && len - i <= 2 && base64.charAt(i) == '='; i--) {
            padding++;
        }
        return len / 4 * 3 - padding;
    }

    /**
     * 카드가 그릴 형태로 투영 — 후보 행과 ④ 브라우저는 <b>같은 {@link ScreenOption}</b>
     * 이다. 어느 쪽을 눌러도 확정 결과가 screen-map id 하나로 같아야 하기 때문이다.
     */
    private static ScreenClassification project(ScreenClassifier.Classification result) {
        List<ScreenOption> candidates = result.candidates().stream()
                .map(c -> new ScreenOption(c.id(), c.name(), c.menuLabel()))
                .toList();

        List<BrowseGroup> browse = new ArrayList<>();
        for (Map.Entry<String, List<ScreenMap>> group : result.byProgram().entrySet()) {
            browse.add(new BrowseGroup(
                    group.getKey(),
                    group.getValue().stream()
                            .map(m -> new ScreenOption(
                                    m.id(), m.name(), ScreenClassifier.menuLabel(m)))
                            .toList()));
        }
        return new ScreenClassification(candidates, browse);
    }
}

package fdc.agent.lines;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * akg 의 {@code fab-line} 문서 집합에서 라인 목록을 런타임 fetch 하는
 * {@link LineSource} — {@code AkgSkillSource} 와 같은 규율이다.
 *
 * <p><b>본문을 안 읽는다</b> — akg 의 {@code fab-line} 은 {@code id = lower(code)}
 * 이고 {@code code} 는 대문자·숫자·{@code _}·{@code -} 만 허용한다. 그래서
 * {@code upper(id)} 가 코드를 정확히 되돌린다(소문자 글자가 애초에 없으므로 왕복이
 * 무손실이다). 라인 하나마다 문서를 한 번씩 더 가져올 이유가 없다.
 *
 * <p><b>실패 정책</b> — 챗과 같다(fail-open): 허브에 한 번도 못 닿았으면 빈 목록,
 * 닿은 적 있으면 마지막 정상 스냅샷을 유지한다. 라인을 못 받았다고 화면이 죽지
 * 않고, 대신 <b>없는 라인을 지어내지도 않는다</b>.
 *
 * <p>status=active 만 수용한다(inactive/archived = 폐기된 라인 — 드롭다운에서 빠져야
 * 하지만 이미 그 라인으로 등록된 설비 카드는 건드리지 않는다).
 */
public final class AkgLineSource implements LineSource {

    private static final Logger log = LoggerFactory.getLogger(AkgLineSource.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final String base;
    private final String token;
    private final long refreshMs;

    // null = 허브에 한 번도 성공적으로 닿지 못함(빈 목록). 첫 성공 이후엔 마지막
    // 정상 스냅샷이 항상 남는다.
    private volatile List<String> hub;
    private volatile long lastAttemptMs;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public AkgLineSource(String url, String token, int refreshSeconds) {
        this.base = url.replaceAll("/+$", "");
        this.token = token == null || token.isEmpty() ? null : token;
        this.refreshMs = Math.max(0, refreshSeconds) * 1000L;
        refresh(); // 기동 시 1회 — 실패해도 뜬다(fail-open).
    }

    @Override
    public List<String> codes() {
        if (System.currentTimeMillis() - lastAttemptMs >= refreshMs
                && refreshing.compareAndSet(false, true)) {
            try {
                refresh();
            } finally {
                refreshing.set(false);
            }
        }
        List<String> snapshot = hub;
        return snapshot == null ? List.of() : snapshot;
    }

    /** 운영용 강제 리로드(#40) — {@link AkgSkillSource#reloadNow()} 와 같은 규율. */
    public boolean reloadNow() {
        if (!refreshing.compareAndSet(false, true)) {
            return false;
        }
        try {
            refresh();
            return true;
        } finally {
            refreshing.set(false);
        }
    }

    private void refresh() {
        lastAttemptMs = System.currentTimeMillis();
        try {
            JsonNode list = getJson("/api/docs?type=fab-line");
            List<String> next = new ArrayList<>();
            for (JsonNode entry : list.path("docs")) {
                if (!"active".equals(entry.path("status").asText())) {
                    continue; // inactive/archived — 폐기된 라인
                }
                String id = entry.path("id").asText("");
                if (id.isBlank()) {
                    continue;
                }
                next.add(id.toUpperCase(Locale.ROOT));
            }
            // 문서 순서는 허브의 사정이라 화면에 그대로 노출할 이유가 없다 —
            // 고르는 사람에게는 늘 같은 자리에 있는 것이 낫다.
            next.sort(String::compareTo);
            if (hub == null || !hub.equals(next)) {
                log.info("akg 라인 로드: {}개 ({})", next.size(), base);
            }
            hub = List.copyOf(next);
        } catch (Exception e) {
            log.warn("akg 허브({}) 라인 조회 실패 — {} 유지: {}", base,
                    hub == null ? "빈 목록" : "마지막 스냅샷", e.toString());
        }
    }

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + " " + path);
        }
        return JSON.readTree(res.body());
    }
}

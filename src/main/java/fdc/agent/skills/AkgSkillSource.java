package fdc.agent.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fdc.agent.akg.AkgSource;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * akg 지식 허브에서 domain-skill spec 을 런타임 fetch 하는 {@link SkillSource}
 * (이슈 #8, claude-hooks B-mode 의 Spring 이식). URL(+토큰)만 설정하면 추가
 * 작업 없이 스킬이 산다 — spec 이 배선(steps[].binds, akg json-spec v0.6.0)까지
 * 담으므로 fetch 한 것만으로 실행이 완결되고, 스킬 추가·수정은 akg push →
 * refresh 주기 내 자동 반영(BE 재배포 없음).
 *
 * <p><b>실패 정책</b> — 챗은 akg 가 죽어도 돌아야 한다(fail-open): 허브에 한
 * 번도 못 닿았으면 classpath 번들, 닿은 적 있으면 마지막 정상 스냅샷 유지.
 * 개별 스킬이 수용 불가(모르는 키·배선 부정합)면 그 스킬만 제외하거나 직전판을
 * 유지하고 나머지는 산다 — 나쁜 문서 하나가 챗 전체를 죽이지 못하게.
 * 허브에 닿은 뒤에는 허브가 진실원이다 — 번들과 합치지 않는다(삭제된 스킬이
 * 번들에서 부활하는 것을 막는다).
 *
 * <p><b>갱신</b> — {@code GET /api/docs?type=domain-skill} 목록(문서별 rev
 * 포함)을 refresh 주기마다 재확인하고, rev 가 바뀐 문서만 다시 fetch 한다.
 * status=active 만 수용한다(inactive/archived = 주입 제외 규율).
 * domain-skill 은 keyword-docs 주입 인덱스에 의도적으로 없으므로
 * {@code /api/index/:type} 이 아니라 문서 목록 API 를 쓴다.
 */
public final class AkgSkillSource implements SkillSource, AkgSource {

    private static final Logger log = LoggerFactory.getLogger(AkgSkillSource.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Cached(String rev, SkillSpec spec) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final String base;
    private final String token;
    private final long refreshMs;

    // null = 허브에 한 번도 성공적으로 닿지 못함(번들 폴백). 첫 성공 이후엔
    // 마지막 정상 스냅샷이 항상 남는다.
    private volatile Map<String, Cached> hub;
    private volatile long lastAttemptMs;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public AkgSkillSource(String url, String token, int refreshSeconds) {
        this.base = url.replaceAll("/+$", "");
        this.token = token == null || token.isEmpty() ? null : token;
        this.refreshMs = Math.max(0, refreshSeconds) * 1000L;
        refresh(); // 기동 시 1회 — 실패해도 번들로 뜬다(fail-open).
    }

    @Override
    public List<SkillSpec> specs() {
        if (System.currentTimeMillis() - lastAttemptMs >= refreshMs
                && refreshing.compareAndSet(false, true)) {
            try {
                refresh();
            } finally {
                refreshing.set(false);
            }
        }
        Map<String, Cached> snapshot = hub;
        if (snapshot == null) {
            return SkillRegistry.bundledSpecs();
        }
        return snapshot.values().stream().map(Cached::spec).toList();
    }

    /**
     * 운영용 강제 리로드(#40) — refresh 주기와 무관하게 즉시 1회 재확인한다.
     * 실패 정책은 주기 refresh 와 동일(fail-open, 기존 스냅샷 유지)이라 결과는
     * 예외가 아니라 반환값으로 나온다.
     */
    @Override
    public Reload reloadNow() {
        if (!refreshing.compareAndSet(false, true)) {
            return Reload.ALREADY_REFRESHING;
        }
        try {
            return refresh() ? Reload.FETCHED : Reload.HUB_UNREACHABLE;
        } finally {
            refreshing.set(false);
        }
    }

    /** @return 허브에서 받아 스냅샷을 갱신했으면 true, 못 닿아 유지했으면 false. */
    private boolean refresh() {
        lastAttemptMs = System.currentTimeMillis();
        try {
            JsonNode list = getJson("/api/docs?type=domain-skill");
            Map<String, Cached> prev = hub;
            Map<String, Cached> next = new LinkedHashMap<>();
            for (JsonNode entry : list.path("docs")) {
                String id = entry.path("id").asText();
                if (!"active".equals(entry.path("status").asText())) {
                    continue; // inactive/archived — 주입 제외
                }
                String rev = entry.path("rev").asText(null);
                Cached had = prev != null ? prev.get(id) : null;
                if (had != null && had.rev() != null && had.rev().equals(rev)) {
                    next.put(id, had); // rev 그대로 → 재fetch 생략
                    continue;
                }
                fetchDoc(id, rev, had, next);
            }
            if (hub == null || changed(hub, next)) {
                log.info("akg 스킬 로드: {}개 ({})", next.size(), base);
            }
            hub = next;
            return true;
        } catch (Exception e) {
            log.warn("akg 허브({}) 접근 실패 — {} 유지: {}", base,
                    hub == null ? "classpath 번들" : "마지막 스냅샷", e.toString());
            return false;
        }
    }

    private void fetchDoc(String id, String listRev, Cached had, Map<String, Cached> next) {
        try {
            JsonNode doc = getJson("/api/docs/domain-skill/"
                    + URLEncoder.encode(id, StandardCharsets.UTF_8));
            if (!"active".equals(doc.path("json").path("status").asText())) {
                return; // 목록과 fetch 사이에 비활성화됨
            }
            SkillSpec spec = JSON.treeToValue(doc.path("json").path("body"), SkillSpec.class);
            SkillLoader.validateSpec(spec); // 나쁜 선언은 여기서 걸러 챗을 못 죽이게
            next.put(id, new Cached(doc.path("rev").asText(listRev), spec));
        } catch (Exception e) {
            if (had != null) {
                next.put(id, had);
                log.warn("akg 스킬 {} 갱신 실패 — 직전판 유지: {}", id, e.toString());
            } else {
                log.warn("akg 스킬 {} 수용 불가 — 제외: {}", id, e.toString());
            }
        }
    }

    /** 로그 소음 방지용 — 키 집합이나 캐시 인스턴스가 바뀐 refresh 만 info 로 남긴다. */
    private static boolean changed(Map<String, Cached> before, Map<String, Cached> after) {
        if (!before.keySet().equals(after.keySet())) {
            return true;
        }
        for (Map.Entry<String, Cached> e : before.entrySet()) {
            if (after.get(e.getKey()) != e.getValue()) {
                return true;
            }
        }
        return false;
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

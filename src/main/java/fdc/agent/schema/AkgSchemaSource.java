package fdc.agent.schema;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * akg 지식 허브에서 {@code db-schema} 문서를 런타임 fetch 하는 {@link SchemaSource}
 * (이슈 #49) — {@code AkgSkillSource} 와 같은 규율이다: rev 비교로 바뀐 문서만
 * 재fetch, status=active 만 수용, fail-open.
 *
 * <p><b>번들 폴백이 없다</b> — 스킬과 달리 컬럼 의미는 classpath 에 대체본이 없다.
 * 허브에 한 번도 못 닿았으면 발췌 절이 그냥 없는 채로 서술한다({@link SchemaSource#NONE}
 * 과 같은 관측 결과).
 *
 * <p>문서 id = {@code lower(table)} — {@link #byTable(String)} 조회 시 대소문자를
 * 맞춘다. {@code columnDescs[*]} 는 tiered-value 라 {@code tier=deprecated} 인
 * 항목은 파싱 시점에 걸러 낸다(주입 제외 규율, status=active 와 같은 취지이되 문서가
 * 아니라 필드 단위).
 */
public final class AkgSchemaSource implements SchemaSource, AkgSource {

    private static final Logger log = LoggerFactory.getLogger(AkgSchemaSource.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Cached(String rev, SchemaDoc doc) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final String base;
    private final String token;
    private final long refreshMs;

    // null = 허브에 한 번도 성공적으로 닿지 못함 — 빈 상태(발췌 절 없음). 첫 성공
    // 이후엔 마지막 정상 스냅샷이 항상 남는다.
    private volatile Map<String, Cached> hub;
    private volatile long lastAttemptMs;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public AkgSchemaSource(String url, String token, int refreshSeconds) {
        this.base = url.replaceAll("/+$", "");
        this.token = token == null || token.isEmpty() ? null : token;
        this.refreshMs = Math.max(0, refreshSeconds) * 1000L;
        refresh(); // 기동 시 1회 — 실패해도 뜬다(fail-open).
    }

    @Override
    public Optional<SchemaDoc> byTable(String table) {
        maybeRefresh();
        Map<String, Cached> snapshot = hub;
        if (snapshot == null || table == null || table.isBlank()) {
            return Optional.empty();
        }
        Cached hit = snapshot.get(table.trim().toLowerCase(Locale.ROOT));
        return hit != null ? Optional.of(hit.doc()) : Optional.empty();
    }

    @Override
    public int size() {
        Map<String, Cached> snapshot = hub;
        return snapshot != null ? snapshot.size() : 0;
    }

    /** 운영용 강제 리로드(#40) — 스킬·라인과 같은 규율. */
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

    private void maybeRefresh() {
        if (System.currentTimeMillis() - lastAttemptMs >= refreshMs
                && refreshing.compareAndSet(false, true)) {
            try {
                refresh();
            } finally {
                refreshing.set(false);
            }
        }
    }

    /** @return 허브에서 받아 스냅샷을 갱신했으면 true, 못 닿아 유지했으면 false. */
    private boolean refresh() {
        lastAttemptMs = System.currentTimeMillis();
        try {
            JsonNode list = getJson("/api/docs?type=db-schema");
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
                log.info("akg 스키마 로드: {}개 ({})", next.size(), base);
            }
            hub = next;
            return true;
        } catch (Exception e) {
            log.warn("akg 허브({}) 접근 실패 — {} 유지: {}", base,
                    hub == null ? "빈 상태" : "마지막 스냅샷", e.toString());
            return false;
        }
    }

    private void fetchDoc(String id, String listRev, Cached had, Map<String, Cached> next) {
        try {
            JsonNode doc = getJson("/api/docs/db-schema/"
                    + URLEncoder.encode(id, StandardCharsets.UTF_8));
            if (!"active".equals(doc.path("json").path("status").asText())) {
                return; // 목록과 fetch 사이에 비활성화됨
            }
            SchemaDoc parsed = parse(doc.path("json").path("body"));
            next.put(id, new Cached(doc.path("rev").asText(listRev), parsed));
        } catch (Exception e) {
            if (had != null) {
                next.put(id, had);
                log.warn("akg 스키마 {} 갱신 실패 — 직전판 유지: {}", id, e.toString());
            } else {
                log.warn("akg 스키마 {} 수용 불가 — 제외: {}", id, e.toString());
            }
        }
    }

    /** {@code body.table}/{@code body.purpose}/{@code body.columnDescs[*]} 만 줍는다. */
    private static SchemaDoc parse(JsonNode body) {
        String table = body.path("table").asText(null);
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("db-schema 문서에 table 이 없다");
        }
        String tableComment = textOf(body.path("purpose"));
        Map<String, String> columnDescs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : body.path("columnDescs").properties()) {
            String text = textOf(e.getValue());
            if (text != null) {
                columnDescs.put(e.getKey(), text);
            }
        }
        return new SchemaDoc(table, tableComment, columnDescs);
    }

    /** tiered-value 에서 text 를 줍되 deprecated 티어는 제외한다. */
    private static String textOf(JsonNode tieredValue) {
        if ("deprecated".equals(tieredValue.path("tier").asText(null))) {
            return null;
        }
        String text = tieredValue.path("text").asText(null);
        return text != null && !text.isBlank() ? text : null;
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

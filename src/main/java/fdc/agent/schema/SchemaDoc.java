package fdc.agent.schema;

import java.util.Map;

/**
 * akg {@code db-schema} 문서에서 뽑은 컬럼 의미 — {@code purpose.text}(테이블 설명)와
 * {@code columnDescs[*].text}(컬럼별 설명)만 남긴다. {@code deprecated} 티어는 이미
 * 걸러진 채로 들어온다({@link AkgSchemaSource} 소관) — 여기 남은 값은 전부 실을 수
 * 있는 것이다.
 */
public record SchemaDoc(String table, String tableComment, Map<String, String> columnDescs) {
}

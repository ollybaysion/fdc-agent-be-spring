package fdc.agent.schema;

import java.util.Optional;

/**
 * 테이블 컬럼 의미 출처 seam — akg {@code db-schema} 문서(이슈 #49). 서술 프롬프트가
 * 발췌를 만들 때 이 호출에 실제로 도착한 테이블만 묻는다 — 안 쓰인 문서를 미리 다
 * 읽어 둘 필요가 없다.
 */
public interface SchemaSource {

    /** 이 테이블의 컬럼 의미. 문서가 없으면 빈 값 — BE 는 의미를 지어내지 않는다. */
    Optional<SchemaDoc> byTable(String table);

    /** 현재 보유한 문서 수 — {@code /admin/reload} 보고용. */
    int size();

    /** akg 미구성 — 컬럼 의미를 못 가져온 채로 서술한다(발췌 절 자체가 안 생긴다). */
    SchemaSource NONE = new SchemaSource() {
        @Override
        public Optional<SchemaDoc> byTable(String table) {
            return Optional.empty();
        }

        @Override
        public int size() {
            return 0;
        }
    };
}

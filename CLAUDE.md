# CLAUDE.md

이 문서는 Claude Code가 이 저장소에서 작업할 때 따라야 할 규칙을 정의합니다.
사람 기여자에게도 동일한 컨벤션이 적용됩니다.

## 프로젝트 개요

**GoodsUp** — 팬덤 굿즈 공동구매 · 마감 임박 결제 시스템.
목표 수량을 달성한 공구만 결제를 요청하고, 마감까지 미달이면 결제 없이 `FAILED`로 종료되는 백엔드 서비스입니다(ADR-0003: 목표 달성 후 결제).
핵심 기술 과제는 마감 시각에 몰리는 **동시 결제 요청의 정합성 제어**입니다.

- 기술 스택: Spring Boot 4.x, Java 26, MySQL, Spring Data JPA, springdoc(Swagger UI), `@Scheduled`(마감 정산·알림·결제 릴레이), 비밀번호 해싱용 `spring-security-crypto`, Testcontainers, jqwik(속성 기반 테스트)
  - Redis(Redisson), Spring Batch, Spring Security(JWT)는 현재 사용하지 않는다. 필요해지면 ADR로 근거를 남긴 뒤 이 문서에 먼저 추가한다.
- 백엔드 프로젝트 루트는 `back/` 디렉터리입니다 (Gradle Kotlin DSL).
- 아키텍처: Controller → Service → Repository 3계층 구조. 도메인별 패키지 구성(`goods`, `orders`, `payment`, `notification`, `user`)

## 아키텍처 원칙

- **Controller**: 요청/응답 DTO 변환과 검증(`@Valid`)만 담당. 비즈니스 로직을 넣지 않는다.
- **Service**: 트랜잭션 경계(`@Transactional`)는 Service 계층에서만 선언한다. 트랜잭션 범위는 최대한 좁게 유지한다.
- **Repository**: JPA 쿼리 메서드 또는 `@Query`만 작성. 비즈니스 로직 금지.
- 공통 응답은 `CustomApiResponse<T>` 래퍼를 사용한다. 에러는 `GlobalExceptionHandler`에서 일괄 처리하며, 도메인별 커스텀 예외는 `xxxException`으로 명명한다.
- 동시성이 필요한 로직(재고 차감, 마감 판정)은 반드시 명시적인 락으로 보호한다(락 없이 단순 `SELECT` 후 `UPDATE` 금지).
  - **공구 참여(재고 차감)**: `GoodsFundingRepository.findByIdForUpdate`(`@Lock(PESSIMISTIC_WRITE)`)로 DB 비관적 락을 사용한다. Redisson RLock, 낙관적 락+재시도와 실측 비교한 결과이며 근거는 `docs/adr/0001-concurrency-control-strategy.md` 참고.
  - 그 외 새로운 동시성 로직(마감 정산 등)에 대해 아직 실측 비교가 없다면, 코드 작성 전에 Plan Mode로 전략을 비교하고 ADR을 작성한다. Redisson RLock을 쓰는 경우 락 획득/해제는 서비스 메서드 내에서 try-finally로 명시적으로 처리한다.

- **CDC(Debezium + Kafka)는 ADR-0004 B안 경량 PoC 용도로만, 테스트 스코프(Testcontainers)에서만 사용한다.** 프로덕션 코드·런타임 의존성·배포 인프라로의 도입은 ADR-0004 결정이 확정된 뒤 이 문서를 다시 갱신한 후에 진행한다.

## 코딩 컨벤션

- 패키지 구조: `com.goodsup.demo.{domain}.{controller|service|dto|domain}` (엔티티·리포지토리는 `domain` 패키지)
- 네이밍: 클래스는 PascalCase, 메서드/변수는 camelCase, 상수는 UPPER_SNAKE_CASE
- DTO는 요청(`XxxRequest`)과 응답(`XxxResponse`)을 분리하고, Entity를 API 응답에 직접 노출하지 않는다.
- Lombok은 `@Getter`, `@Builder`, `@RequiredArgsConstructor`만 사용한다. `@Data`, `@Setter`는 엔티티에 사용하지 않는다(불변성 유지).
- 로그는 `Logback` 사용, 운영 이슈 추적이 필요한 지점(락 획득 실패, 결제 실패, 배치 판정)은 반드시 INFO 이상 레벨로 로그를 남긴다.

## 금지 패턴

- **트랜잭션 안에서 외부 API를 동기 호출하지 않는다.** (화록 프로젝트에서 겪은 DB 커넥션 점유 문제 재발 방지)
- **N+1 쿼리를 유발하는 연관관계 접근 금지.** 필요한 경우 `fetch join` 또는 `@EntityGraph`를 사용한다.
- 서비스 메서드에서 다른 서비스의 Repository에 직접 접근하지 않는다. 반드시 해당 서비스를 통해서만 접근한다.
- 재고(참여 수량) 관련 로직은 락 없이 단순 `SELECT` 후 `UPDATE`로 구현하지 않는다.

## 테스트 규칙

- 모든 PR은 최소 하나 이상의 테스트를 포함해야 한다. 테스트 없는 PR은 머지하지 않는다.
- 동시성 로직(참여/결제, 마감 정산)은 반드시 동시 요청을 재현하는 통합 테스트를 작성한다(`ExecutorService` 기반 동시 호출 테스트 또는 Testcontainers 활용).
- 단위 테스트는 JUnit5 + Mockito, 통합 테스트는 `@SpringBootTest` + Testcontainers(MySQL)를 사용한다(Kafka는 ADR-0004 CDC PoC 전용 테스트 스코프).
- 동시성/결제 정합성과 관련된 **불변식**(예: "참여 수량은 목표 수량을 절대 초과하지 않는다", "결제 금액 합계는 항상 정합한다", "정산 후 상태 전이는 되돌아가지 않는다")은 `jqwik`(JUnit5 통합) 기반 property-based test로 검증한다.
  - 무작위 커맨드 시퀀스를 생성해 매 단계마다 불변식이 유지되는지 확인하는 stateful property test를 우선 활용한다.
  - `ExecutorService` 기반 동시 요청 재현 테스트를 대체하지 않고 **보완**한다 — 사람이 미리 떠올린 특정 시나리오 밖의 반례를 찾는 용도이며, 재현된 반례는 반드시 example-based 회귀 테스트로도 고정한다.
- 커밋 전 훅(`.githooks/pre-commit`)이 `./gradlew test`를 실행하며, 테스트가 통과해야 커밋할 수 있다. GitHub Actions는 현재 Claude PR 리뷰만 수행하고 빌드·테스트는 돌리지 않는다. AI가 생성한 코드도 예외 없이 동일한 기준을 적용한다.

## Claude Code 사용 원칙

- **자유롭게 위임 가능**: CRUD 컨트롤러/서비스/리포지토리 보일러플레이트, DTO 변환 코드, 테스트 케이스 초안, 커밋 메시지·PR 설명 초안
- **Plan Mode를 먼저 사용**: 동시성 제어 전략, 마감 정산 배치 알고리즘, 트랜잭션 경계처럼 되돌리기 어려운 설계는 코드를 바로 작성하지 않고 Plan Mode로 대안을 비교 검토한 뒤, 최종 선택과 근거는 `docs/adr/`에 직접 문서화한다.
- **새 이슈 작업을 시작하기 전, 해당 결정이 ADR 대상인지 먼저 판단한다.** 대상이면 구현 전에 `docs/adr/`에 다음 번호로 초안을 먼저 작성하고 사람의 확인을 받은 뒤 진행한다.
- 새로운 라이브러리·아키텍처 패턴을 도입할 때는 먼저 이 문서에 원칙을 추가한 뒤 코드를 작성한다.
- 커밋 전 Claude가 생성한 코드는 반드시 라인 단위로 리뷰한다. PR 설명에 AI 활용 범위를 명시한다(PR 템플릿 참고).

### AI를 검증 파트너로 활용

Claude는 코드 생성뿐 아니라 구현의 반례를 찾는 적대적 검증자(Adversarial Reviewer)로 활용한다.
특히 동시성 제어, 분산락, 결제 상태 전이, 마감 정산과 같이 장애 발생 시 데이터 정합성에 영향을 주는 로직은 다음 순서로 검증한다.

1. Claude에게 정상 동작을 가정하지 않고 실패 가능한 실행 순서와 엣지 케이스를 생성하도록 요청한다.
2. 생성된 시나리오 중 실제 재현 가능성이 있는 케이스를 선정한다.
3. 해당 시나리오를 통합 테스트 또는 부하 테스트로 구현한다.
4. 실제 실행 결과와 Claude의 가설을 비교한다.
5. 버그가 재현된 경우 원인을 직접 분석하고 수정한다.
6. 수정 후 동일한 공격 시나리오를 재실행하여 해결 여부를 검증한다.

Claude의 분석이나 예측 자체를 근거로 최종 결론을 내리지 않는다.
실제 테스트 결과와 관찰 가능한 데이터만 최종 의사결정의 근거로 사용한다.

재현 여부와 상관없이 모든 시나리오는 `docs/experiments/` 아래 주제별 로그 파일
(`adversarial-test-log-<주제>.md`, 예: 결제는 `adversarial-test-log-payment.md`)에 기록한다.
전체 목록은 `docs/experiments/adversarial-test-log.md`(인덱스)에서 찾는다. 다루는 이슈에 맞는
주제 파일이 아직 없으면 새로 만들고 인덱스에 등록한다.

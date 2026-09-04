# CLAUDE.md

이 문서는 Claude Code가 이 저장소에서 작업할 때 따라야 할 규칙을 정의합니다.
사람 기여자에게도 동일한 컨벤션이 적용됩니다.

## 프로젝트 개요

**GoodsUp** — 팬덤 굿즈 공동구매 · 마감 임박 결제 시스템.
목표 수량 달성 시에만 결제가 확정되고, 미달 시 전액 자동 환불되는 백엔드 서비스입니다.
핵심 기술 과제는 마감 시각에 몰리는 **동시 결제 요청의 정합성 제어**입니다.

- 기술 스택: Spring Boot 3.x, Java 21, MySQL, Redis(Redisson), Spring Batch, Spring Security(JWT)
- 백엔드 프로젝트 루트는 `back/` 디렉터리입니다 (Gradle Kotlin DSL).
- 아키텍처: Controller → Service → Repository 3계층 구조. 도메인별 패키지 구성(`goods`, `orders`, `payment`, `notification`, `user`)

## 아키텍처 원칙

- **Controller**: 요청/응답 DTO 변환과 검증(`@Valid`)만 담당. 비즈니스 로직을 넣지 않는다.
- **Service**: 트랜잭션 경계(`@Transactional`)는 Service 계층에서만 선언한다. 트랜잭션 범위는 최대한 좁게 유지한다.
- **Repository**: JPA 쿼리 메서드 또는 `@Query`만 작성. 비즈니스 로직 금지.
- 공통 응답은 `ApiResponse<T>` 래퍼를 사용한다. 에러는 `GlobalExceptionHandler`에서 일괄 처리하며, 도메인별 커스텀 예외는 `xxxException`으로 명명한다.
- 동시성이 필요한 로직(재고 차감, 마감 판정)은 반드시 Redisson `RLock` 기반 분산락을 사용하고, 락 획득/해제는 서비스 메서드 내에서 try-finally로 명시적으로 처리한다.

## 코딩 컨벤션

- 패키지 구조: `com.goodsup.{domain}.{controller|service|dto|entity}`
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
- 단위 테스트는 JUnit5 + Mockito, 통합 테스트는 `@SpringBootTest` + Testcontainers(MySQL, Redis)를 사용한다.
- CI(GitHub Actions)에서 빌드와 테스트가 모두 통과해야 머지 가능하다. AI가 생성한 코드도 예외 없이 동일한 기준을 적용한다.

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

재현 여부와 상관없이 모든 시나리오는 `docs/experiments/adversarial-test-log.md`에 기록한다.

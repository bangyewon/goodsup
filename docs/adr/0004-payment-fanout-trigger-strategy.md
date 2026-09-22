# ADR 0004: 정산 후 결제 fan-out 트리거 전략 — Outbox 폴링 릴레이 vs Outbox CDC 릴레이

- 상태: 논의 중 (실측 진행 전 — 비교표·결정은 실측 후 채움)
- 작성일: 2026-09-21
- 관련 이슈: #9
- 관련 ADR: [0001-concurrency-control-strategy.md](./0001-concurrency-control-strategy.md), [0002-deadline-settlement-batch-concurrency.md](./0002-deadline-settlement-batch-concurrency.md), [0003-payment-timing-strategy.md](./0003-payment-timing-strategy.md)

## 배경

ADR-0003이 "B안: 목표 달성 후 결제(Charge-on-settlement)"를 채택하면서, 정산 배치가 상태 전이뿐
아니라 참여자 수만큼의 PG 승인 호출까지 책임지게 됐다. 그런데 CLAUDE.md 금지 패턴("트랜잭션 안에서
외부 API를 동기 호출하지 않는다")과 ADR-0002가 이미 확정한 배치 트랜잭션 경계(`GoodsFundingService`,
`findByIdForUpdate` 비관적 락) 때문에, PG 호출은 상태 확정 트랜잭션 안에 넣을 수 없다. ADR-0003은
이 분리 설계를 "되돌리기 어려운 트랜잭션 경계 결정"으로 보고 별도 Plan Mode 세션으로 미뤄뒀다 —
이 ADR이 그 후속이다.

**중요한 정정**: 착수 전 코드 조사 결과, 결제 fan-out이 필요한 `FINISHED` 확정은 마감 정산 배치가
아니라 **참여 트랜잭션**(`OrderService.participateGoodsFunding`의 `goodsFunding.increaseQuantityAndCloseIfNeeded`
호출 시점, `orders/service/OrderService.java` 28-49행)에서 일어난다. 정산 배치(`GoodsFundingService.settleAsFailed`,
`@Scheduled` 기반)는 `FAILED` 확정만 담당한다. 따라서 이 ADR이 다루는 트랜잭션 분리 지점은 참여
트랜잭션이며, 정산 배치 쪽이 아니다.

### 왜 outbox 패턴인가 (실측 대상이 아닌 이유)

"상태 확정 후 부수효과를 안전하게 트리거"하는 기존 선례는 `DeadlineSettlementScheduler`가 상태
전이 트랜잭션 커밋 후, 같은 스케줄러 메서드 안에서 알림 서비스를 직접 순차 호출하는 패턴뿐이다.
이 패턴은 알림처럼 멱등하고 실패해도 스킵 가능한 부수효과에만 안전하다. 결제 호출을 이 방식
그대로("커밋 후 애플리케이션 코드가 직접 PG를 호출")로 구현하면, 커밋과 호출 사이에 프로세스가
죽는 순간 **`FINISHED` 상태는 영구히 남지만 결제는 한 번도 트리거되지 않는 dual-write 구멍**이
구조적으로 발생한다. 이는 실측할 필요 없이 논리적으로 자명한 결함이라 이번 실험의 후보에서 제외한다.

Outbox 패턴은 "상태를 바꾼다"와 "그 상태 변화에 따라 결제를 트리거해야 한다는 사실을 기록한다"를
같은 DB 트랜잭션에 원자적으로 묶어(둘 다 순수 DB 쓰기라 금지 패턴에 저촉되지 않음) 이 구멍을
구조적으로 막는다. 이번 ADR이 실측으로 비교하는 것은 "outbox를 쓸지 말지"가 아니라, **outbox
row를 누가 읽어서 실제 PG 호출을 수행하는가(릴레이 방식)** 이다.

### 왜 CDC는 경량 타당성 검증으로 축소하는가

CDC(Debezium이 MySQL binlog를 읽어 Kafka로 발행)는 현재 CLAUDE.md 기술 스택(Spring Boot/MySQL/
Redis/Spring Batch/Spring Security/jqwik)에 없는 Kafka+Debezium+Kafka Connect라는 신규 인프라를
요구한다. CLAUDE.md 규칙상 새 아키텍처 패턴 도입은 코드 작성 전에 이 문서에 원칙부터 추가해야
하므로, 채택 여부와 무관하게 이미 도입 비용이 크다. 따라서 "숫자로 정밀 비교"보다는 "치명적으로
불리하지 않은지"만 Testcontainers 기반 경량 PoC로 확인하고, 폴링 릴레이는 ADR-0001/0002와
동일한 수준(완전한 프로덕션급 구현 + 장애주입/멱등성 통합 테스트)으로 실측한다.

## 비교 대상

### A. Outbox + 폴링 릴레이 (완전 구현)

- `OutboxEvent` 엔티티(`payment/domain/`)를 참여 트랜잭션 안에서 `RECRUITING -> FINISHED` 전이와
  같은 트랜잭션에 삽입. `@Scheduled` 기반 `PaymentFanOutRelayScheduler`가 `PENDING` row를 주기적으로
  조회해 `findByIdForUpdate`(ADR-0001/0002와 동일 패턴)로 claim 후, **트랜잭션 밖에서** PG 게이트웨이를
  호출.
- Claim 재경합 시 재시도는 지수 백오프, lease timeout으로 크래시된 워커의 row를 다른 워커가 재claim.
- Idempotency key는 `"order-" + orderId`(`Payment.order_id` unique 제약 기반).

### B. Outbox + CDC 릴레이 (경량 PoC)

- outbox 테이블/삽입 지점은 A와 동일. 차이는 릴레이만 — Debezium이 `outbox_event` 테이블의 binlog
  변경분을 Kafka 토픽으로 발행하고, 컨슈머가 이를 읽어 처리.
- 이번 실험에서는 프로덕션급 컨슈머(재시도/lease/idempotency)를 구현하지 않는다. Testcontainers로
  MySQL(binlog_format=ROW)+Kafka+Debezium Connect를 띄워 "outbox row insert가 실제로 Kafka 토픽에
  나타나는지"와 "커밋~토픽 도달 지연"만 측정한다.

## 실험 설계

상세 시나리오는 구현 단계에서 `docs/experiments/adversarial-test-log.md`에 채워 넣는다(CLAUDE.md
"AI를 검증 파트너로 활용" 프로세스 — 재현 여부와 무관하게 모든 시나리오를 기록). 다루는 축:

- **정합성(A 중심)**: 두 릴레이 인스턴스 동시 claim 시 중복 결제 여부, claim 커밋 후 결제 루프
  도중 크래시 시뮬레이션 후 재시작 시 정확히 이어서 처리되는지(이미 성공한 건 재호출 안 됨),
  좀비 워커(lease 만료 후 원래 워커와 재claim한 워커의 동시 완료 시도) 경합 시 멱등성.
- **지연/성능(참고 지표)**: A의 claim~처리완료 지연, B의 insert~토픽도달 지연(p50/p95, 느슨한
  상한선만 assert).
- **테스트 하네스 함정 대비**: ADR-0002가 이미 겪은 컨테이너 공유/고정 데이터 재사용/타이밍 버퍼
  문제가 이번에도 재현될 수 있음 — B는 기존 `AbstractConcurrencyIntegrationTest`(공유 싱글턴
  컨테이너)를 재사용하지 않고 완전히 격리된 컨테이너 세트를 쓴다.

## 실측 결과

*(실측 진행 후 채움 — `docs/experiments/adversarial-test-log.md`의 관련 항목 링크, 재현/미재현
통계, 발견된 버그와 수정 내용을 여기 요약한다.)*

## 결정

*(실측 후 채움.)*

## 트레이드오프

*(실측 후 채움.)*

## 참고 — 향후 실측이 필요한 하위 질문

- `findByIdForUpdate` claim 재사용 vs `FOR UPDATE SKIP LOCKED` 벌크 claim(ADR-0002가 "그럴듯한
  벌크 UPDATE가 실측에서 8~9배 느렸다"를 이미 증명했으므로, 이번에도 기본값으로 벌크 방식을
  채택하지 않고 별도 실측 대상으로만 참고 지표를 남긴다).
- CDC(B)가 향후 채택 검토 대상이 될 경우, 실제 배포 인프라 도입 시점에 CLAUDE.md 기술 스택 절
  갱신과 신규 아키텍처 원칙 문서화가 선행되어야 한다.
- 실제 PG SDK 연동 이후 idempotency key 설계 재검증(현재는 `LoggingPgPaymentGateway` 플레이스홀더
  기준으로만 설계됨).

# ADR 0004: 정산 후 결제 fan-out 트리거 전략 — Outbox 폴링 릴레이 vs Outbox CDC 릴레이

- 상태: 채택됨 (A안, 2026-09-30 사용자 확정)
- 작성일: 2026-09-21
- 관련 이슈: #9
- 관련 ADR: [0001-concurrency-control-strategy.md](./0001-concurrency-control-strategy.md), [0002-deadline-settlement-batch-concurrency.md](./0002-deadline-settlement-batch-concurrency.md), [0003-payment-timing-strategy.md](./0003-payment-timing-strategy.md)

## 배경

ADR-0003이 "B안: 목표 달성 후 결제(Charge-on-settlement)"를 채택하면서, 정산 배치가 상태 전이뿐
아니라 참여자 수만큼의 PG 승인 호출까지 책임지게 됐다. 그런데 CLAUDE.md 금지 패턴("트랜잭션 안에서
외부 API를 동기 호출하지 않는다")과 ADR-0002가 이미 확정한 잡 트랜잭션 경계(`GoodsFundingService`,
`findByIdForUpdate` 비관적 락) 때문에, PG 호출은 상태 확정 트랜잭션 안에 넣을 수 없다. ADR-0003은
이 분리 설계를 "되돌리기 어려운 트랜잭션 경계 결정"으로 보고 별도 Plan Mode 세션으로 미뤄뒀다 —
이 ADR이 그 후속이다.

**중요한 정정**: 착수 전 코드 조사 결과, 결제 fan-out이 필요한 `FINISHED` 확정은 미달 공구 종료 잡가
아니라 **참여 트랜잭션**(`OrderService.participateGoodsFunding`의 `goodsFunding.increaseQuantityAndCloseIfNeeded`
호출 시점, `orders/service/OrderService.java` 28-49행)에서 일어난다. 미달 공구 종료 잡(`GoodsFundingService.settleAsFailed`,
`@Scheduled` 기반)는 `FAILED` 확정만 담당한다. 따라서 이 ADR이 다루는 트랜잭션 분리 지점은 참여
트랜잭션이며, 종료 잡 쪽이 아니다.

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

상세 시나리오는 구현 단계에서 `docs/experiments/adversarial-test-log-payment.md`에 채워 넣는다(CLAUDE.md
"AI를 검증 파트너로 활용" 프로세스 — 재현 여부와 무관하게 모든 시나리오를 기록). 다루는 축:

- **정합성(A 중심)**: 두 릴레이 인스턴스 동시 claim 시 중복 결제 여부, claim 커밋 후 결제 루프
  도중 크래시 시뮬레이션 후 재시작 시 정확히 이어서 처리되는지(이미 성공한 건 재호출 안 됨),
  좀비 워커(lease 만료 후 원래 워커와 재claim한 워커의 동시 완료 시도) 경합 시 멱등성.
- **지연/성능(참고 지표)**: A의 claim~처리완료 지연, B의 insert~토픽도달 지연(p50/p95, 느슨한
  상한선만 assert).
- **테스트 환경 함정 대비**: ADR-0002가 이미 겪은 컨테이너 공유/고정 데이터 재사용/타이밍 버퍼
  문제가 이번에도 재현될 수 있음 — B는 기존 `AbstractConcurrencyIntegrationTest`(공유 싱글턴
  컨테이너)를 재사용하지 않고 완전히 격리된 컨테이너 세트를 쓴다.

## 실측 결과

### A. Outbox + 폴링 릴레이 (완전 실측 완료)

`PaymentFanOutRelayConcurrencyTest`로 CLAUDE.md 절차(시나리오 생성 → 선정 → 통합 테스트 구현 →
실측 비교 → 버그 수정 → 재검증)를 완주했다. 상세 표와 회고는
`docs/experiments/adversarial-test-log-payment.md`의 "PaymentFanOutRelayScheduler/OutboxEventService/
PaymentService" 절 참고.

- **정합성**: 5개 시나리오(A1~A5) 중 A2(lease 만료로 인한 재claim 경합) 1건이 실제로 재현됐다.
  두 워커가 lease 재claim 윈도우에서 같은 결제를 PG에 중복 호출할 수 있고(실측: 동일 주문 PG
  호출 2회), 뒤늦게 완료 처리하려는 워커가 이미 `PROCESSED`인 outbox row에 대해
  `IllegalStateException`을 던져 `runOnce`의 `DataAccessException` catch를 뚫고 전파됐다.
  `OutboxEvent.markProcessed()`를 멱등하게(이미 PROCESSED면 무시) 수정해 해결하고 동일 시나리오로
  재검증 완료. PG 외부 호출 자체의 중복(at-least-once)은 이번 수정 대상이 아니며, 기존 설계대로
  idempotency key(`"order-" + orderId`)에 위임한다 — 실제 PG SDK 연동 전까지는 검증되지 않은
  가정으로 남는다(하단 "향후 실측이 필요한 하위 질문" 참고).
  - A1(참여 레이스에도 outbox row 1건), A3(이미 성공한 결제는 재시도에서 재호출 안 됨), A4(재시도
    소진 시 성공건 보존 + 실패건만 FAILED 확정)는 모두 재현되지 않음 — ADR-0001/0002가 이미
    검증한 전략(비관적 락, 상태 필터링, 명시적 소진 카운트)이 결제 도메인에서도 그대로 유효했다.
- **지연(참고 지표)**: claim~처리완료 지연, 로컬 MySQL Testcontainers + 즉시 응답하는 fake PG
  기준, n=20, p50=25ms, p95=40~65ms(2회 실측). 실제 PG 왕복 지연이 포함되지 않은 하한선이므로
  프로덕션 SLA 판단 근거로는 쓰지 않는다.
- **테스트 환경 함정 2건도 이 과정에서 발견·수정**: (1) 실측 테스트 자체의 스레드 오케스트레이션
  데드락, (2) 결제 fan-out 기능 추가 이전에 작성된 `OrderConcurrencyIntegrationTest`가 새로 생긴
  Payment/outbox 부수효과를 정리하지 않아 전체 스위트에서만 드러난 교차 오염. 상세는 로그 참고.

### B. Outbox + CDC 릴레이 (경량 PoC 실측 완료)

`OutboxCdcPocTest`(`CDC_POC=true`로만 실행, 격리된 MySQL(binlog ROW)+Kafka+Debezium Connect
컨테이너)로 확인했다. 상세와 해석 주의점은 `docs/experiments/adversarial-test-log-payment.md`의
"B안(CDC) 경량 PoC 실측" 절 참고.

- **도달 여부**: outbox insert가 Debezium을 거쳐 Kafka 토픽에 실제로 나타났다(워밍업 1건 + 측정 20건
  전부 도달).
- **지연**: 커밋~토픽 도달 n=20, p50=496ms, p95=502ms. 분포가 485~509ms로 매우 좁아 Debezium
  기본 폴링 간격(500ms로 알고 있음)이 지배했을 가능성이 크나, 설정을 바꿔 확인하지는 않았다(가설).
- **측정하지 않은 것**: 동시 부하, 커넥터 재시작 시 offset 재개·중복 발행, 컨슈머 멱등성/재시도/lease.
  프로덕션급 컨슈머를 구현하지 않았으므로 A안과 정합성 측면의 비교는 할 수 없다.
- **A와 직접 비교 불가**: A의 p50=25ms는 "claim~처리완료"이고 B의 496ms는 "커밋~토픽 도달"이라
  구간이 다르다. A의 트리거 지연은 스케줄러 cron 설정(기본 1분)에서 나오며 이번에 실측하지 않았다.

## 결정

**A안(Outbox + 폴링 릴레이)을 채택한다.**

- 근거(실측): A는 정합성 실측을 통과했고(발견된 버그 수정·재검증 완료) 신규 인프라 없이 기존
  스택만으로 동작한다. B는 outbox→토픽 도달 자체는 확인됐지만(p50≈0.5초), 이는 "치명적으로 불리하지
  않은가"를 본 경량 PoC라 정합성·장애 복구는 검증되지 않았다.
- 근거(정책 판단, 실측 아님): B의 이점은 트리거 지연(≈0.5초 vs A의 cron 주기)인데, 정산 후 결제는
  참여자에게 초 단위 즉시성을 요구하지 않는다고 본다. 반면 B는 Kafka+Connect 운영 부담과 CLAUDE.md
  기술 스택 갱신을 요구한다. 이 "즉시성이 필요 없다"는 판단은 데이터가 아니라 가정이며, 사용자가 A안을 확정하며 받아들였다.
- A의 트리거 지연이 문제되면 cron 주기 단축이 먼저이며, 그 이후에도 부족할 때 B를 재검토한다.
  이 경우 CLAUDE.md의 CDC 원칙(테스트 스코프 한정)을 먼저 갱신해야 한다.

### 재시도/lease 상수 확정 (`MAX_ATTEMPTS`/`LEASE_TIMEOUT`/`BASE_BACKOFF`/`MAX_BACKOFF`)

`PaymentFanOutRelayScheduler`의 이 네 상수는 원래 실측 근거 없는 임시 기본값(`MAX_ATTEMPTS=5`,
`LEASE_TIMEOUT=5분`, `BASE_BACKOFF=10초`, `MAX_BACKOFF=10분`)이었다. A6(`docs/experiments/
adversarial-test-log-payment.md` "후속: LEASE_TIMEOUT 근거 마련을 위한 감도 분석")이 "릴레이 자체
오버헤드는 참여자당 약 20~25ms이고 처리시간은 N에 선형 비례한다"는 것까지는 실측으로 확인했지만,
공식을 구체적인 숫자로 채우려면 실측으로는 못 메우는 입력값 세 개가 더 필요했다. 이건 관찰 데이터가
아니라 **사용자가 명시적으로 결정한 정책 가정**이므로, 실측 결과와 섞이지 않도록 근거를 따로
남긴다.

**사용자가 확정한 정책 가정 3가지**

| 입력값 | 확정값 | 사용자가 선택한 근거 |
|---|---|---|
| PG 타임아웃 가정 | 5초 | 국내 PG사가 실제로는 수초 내 응답하는 경우가 대부분이라는 판단하에, 타임아웃 스펙 상한값(보수적 대안 30초)보다 실제 체감 응답시간에 가까운 값을 선택 |
| 공구 1건당 최대 참여자 수(maxN) | 1000명 | 대규모/인기 콘텐츠 기준의 보수적 상한 — `GoodsFunding`에 `targetQuantity` 코드 상한이 없어 실측으로 얻을 수 없는 값이라 비즈니스 정책으로 직접 지정 |
| 재시도 소진까지 허용 SLA | 약 1시간 이내 | PG사 일시적 장애가 대개 수분~수십분 내 복구된다고 보고, 그 구간 동안은 계속 재시도하는 쪽(너무 빨리 포기하지도, 참여자를 반나절 이상 기다리게 하지도 않는 중간 지점)을 선택 |

**derivation**

- `LEASE_TIMEOUT ≈ maxN × (PG 타임아웃 + 릴레이 오버헤드) = 1000 × (5000ms + 25ms) = 5,025,000ms
  ≈ 83.75분` → 90분으로 반올림(약간의 여유분 포함). *(주의: 이 "83.75분"이라는 최악값 자체는
  이미 "재시도 SLA 약 1시간"보다 길다 — 다만 이 둘은 같은 종류의 시간이 아니다. `LEASE_TIMEOUT`은
  "참여자 1000명 전원이 PG 타임아웃 5초를 전부 채운다"는 최악의 크래시 감지 대기시간이고, 재시도
  SLA는 정상적으로 실패 응답을 받은 뒤 재시도를 반복하는 시간이라 보통은 겹치지 않는다. 다만
  1000명 규모 공구에서 워커가 실제로 죽으면 복구까지 최대 90분 가까이 걸린다는 트레이드오프는
  그대로 남고, 사용자가 maxN=1000/재시도 SLA 1시간을 각각 확정하며 이 트레이드오프를 받아들인
  것으로 본다.)*
- `MAX_ATTEMPTS`: `BASE_BACKOFF`(10초)·`MAX_BACKOFF`(10분)는 그대로 두고 재시도 횟수만 SLA에
  맞춘다. `attemptCount=1~5`는 지수 증가(20·40·80·160·320초), `attemptCount=6~10`은 상한(600초)에
  도달해 고정 — 합계 620 + 600×5 = 3620초 ≈ **60.3분**. 즉 `MAX_ATTEMPTS=11`(11번째 실패에서
  `FAILED` 확정)이 "약 1시간 이내" 목표에 정확히 들어맞아, `BASE_BACKOFF`/`MAX_BACKOFF`는 변경하지
  않았다.

**최종값**

| 상수 | 이전(임시값) | 확정값 | 근거 |
|---|---|---|---|
| `MAX_ATTEMPTS` | 5 | **11** | 재시도 SLA(~1시간) 역산 |
| `LEASE_TIMEOUT` | 5분 | **90분** | maxN(1000) × PG 타임아웃(5초) 실측 공식 |
| `BASE_BACKOFF` | 10초 | 10초(유지) | 위 역산에서 변경 불필요로 확인 |
| `MAX_BACKOFF` | 10분 | 10분(유지) | 위 역산에서 변경 불필요로 확인 |

**남은 한계**: PG 타임아웃(5초)과 maxN(1000명)은 실측이 아니라 정책 가정이다. 실제 PG SDK 연동
후 관찰되는 왕복 지연이 5초와 크게 다르거나, 운영 데이터로 참여자 수 분포가 1000명과 크게 다르면
이 네 값은 재산정이 필요하다.

## 트레이드오프

| | A. 폴링 릴레이 | B. CDC 릴레이 |
|---|---|---|
| 신규 인프라 | 없음 | Kafka + Debezium Connect |
| 트리거 지연 | cron 주기에 좌우(기본 1분, 미실측) | p50=496ms / p95=502ms (PoC, n=20) |
| 정합성 검증 | 완료(A1~A5, A2 수정) | 미검증(PoC 범위 밖) |
| 장애 복구·중복 처리 | lease/재시도/멱등 구현·검증 | 미구현 |
| 운영 부담 | 기존 스택 | 커넥터·offset·스키마 히스토리 관리 추가 |

*(B의 지연 수치는 로컬 Docker·단일 브로커·순차 insert 기준이며 프로덕션 SLA 근거로 쓰지 않는다.)*

## 참고 — 향후 실측이 필요한 하위 질문

- `findByIdForUpdate` claim 재사용 vs `FOR UPDATE SKIP LOCKED` 벌크 claim(ADR-0002가 "그럴듯한
  벌크 UPDATE가 실측에서 8~9배 느렸다"를 이미 증명했으므로, 이번에도 기본값으로 벌크 방식을
  채택하지 않고 별도 실측 대상으로만 참고 지표를 남긴다).
- CDC(B)가 향후 채택 검토 대상이 될 경우, 실제 배포 인프라 도입 시점에 CLAUDE.md 기술 스택 절
  갱신과 신규 아키텍처 원칙 문서화가 선행되어야 한다.
- 실제 PG SDK 연동 이후 idempotency key 설계 재검증(현재는 `LoggingPgPaymentGateway` 플레이스홀더
  기준으로만 설계됨).
- 실제 PG SDK 연동 후 위 "재시도/lease 상수 확정" 절의 정책 가정(PG 타임아웃 5초, maxN 1000명)을
  관찰 데이터로 재검증하고, 크게 어긋나면 `MAX_ATTEMPTS`/`LEASE_TIMEOUT`을 재산정한다.

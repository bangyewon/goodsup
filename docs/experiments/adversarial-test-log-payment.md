# 적대적 동시성 테스트 로그 — 결제 fan-out (이슈 #9)

[인덱스로 돌아가기](./adversarial-test-log.md)

## 발견 기록

대상 로직: `PaymentFanOutRelayScheduler`/`OutboxEventService`/`PaymentService`의 결제 fan-out
claim/lease/재시도(ADR-0004 A안: Outbox + 폴링 릴레이, 이슈 #9). CLAUDE.md 절차대로 Claude가 먼저
후보 시나리오(A1~A5)를 제안하고, 사람이 전부 선정해 `PaymentFanOutRelayConcurrencyTest`로 재현했다.

| # | 시나리오 | 실제 재현 여부 | 원인 | 수정 내용 | 관련 커밋 |
|---|---|---|---|---|---|
| A1 | 참여 레이스로 마지막 한자리를 두고 경합해도 `PAYMENT_FANOUT_REQUESTED` outbox row가 정확히 1건만 생성되는가 | 재현 안 됨(정상 동작 확인) | `OrderService.participateGoodsFunding`이 `GoodsFunding` 비관적 락 트랜잭션 안에서 Payment 생성·outbox insert까지 원자적으로 묶어, 목표 달성 전이가 단 한 번만 일어남(ADR-0001과 동일한 보호) | - | - |
| A2 | lease(`LEASE_TIMEOUT`) 초과로 다른 워커가 같은 outbox row를 재claim하면, 원래 워커와 새 워커가 동시에 같은 결제를 PG에 중복 호출하고, 뒤늦게 완료 처리하려는 워커가 예외를 던질 수 있다 | **재현됨** | PG 호출은 트랜잭션 밖에서 수행되므로(금지 패턴 회피) claim 이후엔 lease만으로 소유권을 주장한다 — 두 워커가 동시에 `chargeOne`을 호출하면 PG 호출 자체가 중복되고(실측: `order1 PG 호출 횟수=2`), 뒤늦은 워커가 이미 `PROCESSED`인 row에 `OutboxEvent.markProcessed()`를 호출하며 `IllegalStateException`이 `runOnce`의 `DataAccessException` catch를 뚫고 전파됨(실측 로그: `worker1Failure=java.lang.IllegalStateException: PROCESSING 상태에서만 완료 처리할 수 있습니다`) | `OutboxEvent.markProcessed()`가 이미 `PROCESSED`면 예외 대신 조용히 흡수하도록 변경(`Payment.markSucceeded/markFailed`와 동일한 멱등 패턴). PG 이중 호출 자체(외부 호출 레벨의 at-least-once)는 idempotency key(`"order-" + orderId`)에 의존하는 기존 설계를 그대로 유지 — DB 상태(Payment)는 두 경우 모두 정합했다 | 이번 커밋(OutboxEvent/PaymentFanOutRelayConcurrencyTest) |
| A3 | 이미 `SUCCEEDED`인 결제가 다음 재시도 사이클에서 다시 PG 호출되지 않는가 | 재현 안 됨(정상 동작 확인) | `PaymentService.findRequestedPaymentIdsByGoodsFundingId`가 `status=REQUESTED`만 조회해 이후 사이클의 후보 목록에서 자연히 제외됨(실측: 성공건 PG 호출 1회 고정) | - | - |
| A4 | `MAX_ATTEMPTS` 소진 후 `markAllRemainingRequestedAsFailed`가 이미 `SUCCEEDED`된 결제는 보존하고 `REQUESTED`만 `FAILED`로 확정하는가 | 재현 안 됨(정상 동작 확인) | 실패건만 재시도 소진 후 `FAILED` 확정, 성공건은 그대로 `SUCCEEDED` 유지(실측 로그: `성공건 PG 호출=1회, 실패건 PG 호출=5회`, 당시 `MAX_ATTEMPTS=5` 기준) | - | - |
| A5 | claim~처리완료 지연(참고 지표) | 해당 없음(버그 시나리오 아님) | 로컬 MySQL Testcontainers + 즉시 응답하는 fake PG 기준, n=20, p50=25ms, p95=40~65ms(2회 실측) | - | - |
| A6 | 참여자 수(N)와 PG 호출 1건당 지연(L)에 따라 한 outbox row 처리 소요시간이 어떻게 늘어나는가(LEASE_TIMEOUT 근거 마련용, 버그 시나리오 아님) | 해당 없음(감도 분석) | `chargeOne`이 순차 호출이라 총 소요시간이 N에 선형 비례함을 확인. 단일 샘플(n=1/조합) 실측: L=0ms일 때 N=5→76ms, N=20→163ms, N=50→331ms(참여자당 6.6~15.2ms, N이 커질수록 고정 오버헤드 비중이 줄며 수렴). L=100ms일 때 N=5→625ms, N=20→2399ms, N=50→6112ms(참여자당 120~125ms, 즉 시뮬레이션 지연 대비 릴레이 자체 오버헤드는 참여자당 약 20~25ms) | - | - |
| A2-하네스 | (테스트 하네스 자체의 결함) A2를 처음 작성했을 때 "재claim한 워커(worker-2)" 호출을 메인 스레드에서 동기로 실행하고, 블록을 푸는 코드(`order1Release.countDown()`)를 그 다음 줄에 둠 — worker-2도 worker-1과 같은 블록 지점(order1)에서 멈추므로, 블록을 풀어야 할 메인 스레드 자신이 먼저 블록돼버려 전체가 데드락됨(jstack으로 확인: 32분 경과, 워커 CPU 시간 0.02초 미만) | **재현됨** | "블록을 거는 지점"과 "그 블록을 푸는 지점"이 같은 실행 흐름(메인 스레드) 안에 있어, 자기 자신이 필요로 하는 release를 실행할 수 없는 자기 참조 데드락이었다 | 두 워커 모두 `ExecutorService`의 백그라운드 스레드로 제출하고, `worker1Started`/`worker2Started` 두 개의 개별 래치로 "둘 다 블록에 진입했음"을 확인한 뒤에야 메인 스레드에서 release하도록 재작성 | 이번 커밋(PaymentFanOutRelayConcurrencyTest) |
| 전체스위트-하네스 | (테스트 하네스 자체의 결함) A2 수정 후 개별 클래스 실행은 전부 통과했는데, 전체 스위트(`./gradlew test`)로 돌리자 A3/A4가 `expected: 0 but was: 1`로 실패 | **재현됨** | ADR-0001 당시 작성된 `OrderConcurrencyIntegrationTest`(목표 수량 30건을 채우는 참여 동시성 테스트)에 `@AfterEach` 정리가 전혀 없었다. 이 테스트는 결제 fan-out 기능이 생기기 전에 작성돼, 목표 달성 시 Payment/outbox row가 같이 생긴다는 사실을 반영하지 못한 채 방치돼 있었다 — 싱글턴 MySQL 컨테이너를 공유하는 다른 클래스가 이 남은 outbox row까지 자기 실측에 집계해버렸다(내 테스트가 원인이 아니라, 새 기능이 기존 테스트의 암묵적 전제를 깬 사례) | 다른 4개 동시성 테스트 클래스와 동일하게 `PaymentRepository`/`OutboxEventRepository`를 주입받아 FK 순서(payment → outbox_event → orders → goods_funding → user)로 정리하는 `@AfterEach` 추가 | 이번 커밋(OrderConcurrencyIntegrationTest) |

## 회고 (#9, ADR-0004 A안)

- A1/A3/A4는 기존 전략(ADR-0001의 비관적 락 재사용, `REQUESTED` 필터링, 명시적 소진 카운트)이
  새 도메인(결제 fan-out)에서도 그대로 유효함을 확인했다 — 코드는 새로웠지만 새로운 종류의
  반례는 없었다.
- A2는 이번 실측에서 유일하게 재현된 프로덕션 버그다. 지난 턴 분석에서 "PG가 중복 호출될 수
  있다"는 예측은 idempotency key 설계로 이미 어느 정도 받아들여진 리스크였지만, 실제로 재현해보니
  진짜 문제는 PG 중복 호출 자체가 아니라 **그 경합 이후 두 워커가 모두 "내가 마무리해야 한다"고
  믿고 완료 처리를 시도하면서 발생하는 미처리 예외**였다. 이는 분석만으로는 예측하기 어려웠고,
  실제로 스레드를 블로킹시켜 재현한 뒤에야 정확한 실패 지점(`markProcessed`의 상태 가드)이
  드러났다 — CLAUDE.md가 "Claude의 예측 자체를 근거로 결론 내리지 않는다"고 못박은 이유를 그대로
  보여준다.
- 프로덕션 버그를 재현하려는 동시성 테스트 자체가 별도의 동시성 버그(데드락)를 갖고 있었다.
  "블록을 거는 주체와 그 블록을 푸는 주체가 같은 실행 흐름에 있으면 안 된다"는 교훈은 이후 유사한
  지연 재현 테스트를 작성할 때도 재사용할 수 있다.
- 개별 클래스 실행에서는 안 보이고 전체 스위트에서만 드러나는 결함도 있었다. 새 기능(결제
  fan-out)이 기존 코드(`OrderService.participateGoodsFunding`)의 부수효과를 늘렸는데, 그 변경
  이전에 작성된 테스트(`OrderConcurrencyIntegrationTest`)는 정리 로직을 갱신할 이유 자체가 없었으므로
  당시엔 결함이 아니었다. "새 부수효과를 추가하는 변경은 그 부수효과를 만들어낼 수 있는 기존
  테스트 전부의 정리 로직도 같이 점검해야 한다"는 체크리스트성 교훈으로 남긴다 — 개별 테스트
  통과만으로는 이런 종류의 교차 오염을 잡을 수 없고, 전체 스위트 실행이 필수인 이유이기도 하다.

## 후속: LEASE_TIMEOUT 근거 마련을 위한 감도 분석(A6)

`PaymentFanOutRelayScheduler`의 `MAX_ATTEMPTS`/`LEASE_TIMEOUT`/`BASE_BACKOFF`/`MAX_BACKOFF`가
실측 없는 임시 기본값이라는 점을 ADR-0004 "향후 실측이 필요한 하위 질문"에 남긴 뒤, `LEASE_TIMEOUT`
쪽만 먼저 실측을 시도했다. `FakePgPaymentGateway`에 고정 지연(`withLatency`)을 추가하고, 참여자
수(N)를 5/20/50으로, PG 호출 1건당 지연(L)을 0ms/100ms로 바꿔가며 `runOnce()` 소요시간을 쟀다
(`PaymentFanOutRelayConcurrencyTest.A6`).

**한계**: 조합당 1회 샘플(n=1)만 측정했다 — A5(claim~처리완료 지연)처럼 n=20으로 p50/p95를 낼
정도의 반복 측정은 하지 않았다. 그래서 이 수치는 "정확한 지연 분포"가 아니라 "N에 선형 비례하는가,
릴레이 자체 오버헤드가 상수 수준인가"라는 **형태(shape)를 확인하는 감도 분석**으로만 쓴다.

**실측으로 확인된 것**: 총 처리시간이 N에 선형 비례하고(순차 호출 루프이므로 당연한 결과지만
실제로 그렇게 나오는지는 확인이 필요했다), 릴레이 자체의 참여자당 오버헤드(락 재확인·DB 조회·
`markProcessed`/`markPendingForRetry` 커밋 등)가 시뮬레이션 지연(L=100ms) 대비 약 20~25ms 수준으로
작다는 것.

**실측으로 확인되지 않은 것(여전히 남은 가정)**:
- 실제 PG사 승인 API의 왕복 지연(L)·타임아웃 스펙 — PG SDK가 아직 `LoggingPgPaymentGateway`
  플레이스홀더라 관찰 가능한 데이터가 없다. 이번 실측은 L을 0ms/100ms로 가정해 대입해본 것일 뿐,
  실제 L을 측정한 게 아니다.
- 한 공구(funding)의 최대 참여자 수(N) — `GoodsFunding`에 `targetQuantity` 상한을 코드 레벨로
  두지 않아서, "현실적으로 N이 최대 몇까지 가는가"는 코드에서 유도할 수 없는 비즈니스 가정이다.

**도출된 공식(수치가 아니라 형태만)**:

```
outbox row 1건 처리 소요시간 ≈ N × (L + 릴레이 오버헤드) + 고정 오버헤드
LEASE_TIMEOUT ≥ maxN × (PG 타임아웃 스펙 + 릴레이 오버헤드) × 안전마진
```

실측으로 "릴레이 오버헤드"와 "선형 비례"는 근거가 생겼지만, `maxN`과 `PG 타임아웃 스펙`이라는
외부 입력 두 개가 채워지기 전까지는 이 공식이 구체적인 숫자(예: 5분)로 확정될 수 없다. `MAX_ATTEMPTS`/
`BASE_BACKOFF`/`MAX_BACKOFF`는 이번 지연 실측과는 다른 종류의 결정(장애 지속 시 얼마나 오래
재시도를 허용할지의 비즈니스 SLA)이라 이번 실측 대상에서 제외했다 — 별도로 다뤄야 한다.

**후속 확정**: 남은 입력값(PG 타임아웃 5초, maxN 1000명, 재시도 SLA 약 1시간)은 사용자가 정책으로
직접 확정했다 — 실측 데이터가 아니라 사용자 결정이므로 이 실험 로그가 아니라 `docs/adr/
0004-payment-fanout-trigger-strategy.md` "결정 — 재시도/lease 상수 확정" 절에 선택 근거와 함께
기록했다. 그 결정과 위 실측 공식을 결합해 `MAX_ATTEMPTS=11`, `LEASE_TIMEOUT=90분`으로 확정하고
`PaymentFanOutRelayScheduler`에 반영했다(`BASE_BACKOFF`/`MAX_BACKOFF`는 역산 결과 변경 불필요로
확인되어 10초/10분 그대로 유지).

# ADR 0002: 미달 공구 종료 잡 동시성 제어 전략

- 상태: 확정 (A 트랙: A-1 채택 / B 트랙: B-1 채택, 실측 완료)
- 작성일: 2026-09-15
- 관련 이슈: #6
- 관련 ADR: [0001-concurrency-control-strategy.md](./0001-concurrency-control-strategy.md)

## 배경

공구 한 건(`GoodsFunding` row)의 상태는 두 경로가 있다.

- **참여 경로**: 마지막 1자리를 채워 목표 수량 달성시 `RECRUITING -> FINISHED`
- **미달 공구 종료 잡**: 마감(`deadlineAt`)이 지났는데 목표 수량 미달시 `RECRUITING -> FAILED`

마감 시각 직전, 마지막 1자리를 두고 여러 참여 요청과 종료 잡이 같은 row에 동시에 접근할 수 있다.
이때 결과는 `FINISHED`와 `FAILED` 중 정확히 하나여야 한다.

별개의 문제로, 잡 자체가 중복 실행될 수 있다. `@Scheduled`는 인스턴스 안에서만 실행을 조율하고
인스턴스 사이의 실행 배제는 제공하지 않으므로, 서버가 둘 이상이면 같은 시각에 각 인스턴스가 같은 잡을 실행한다.
이때도 상태 전이와 알림 발송이 한 번만 반영되어야 한다.

결제·환불은 이 ADR의 범위가 아니므로 후속 ADR에서 다룬다 ([ADR-0003](./0003-payment-timing-strategy.md), [ADR-0005](./0005-partial-fanout-failure-refund-strategy.md) 참고).

이 ADR이 다루는 잡은 두 개다.

|              | 하는 일 | `GoodsFunding` 상태 변경 | 동시 접근 위험 |
|--------------|---|---|---|
| (1) 마감임박 알림  | 마감이 임박한 공구의 참여자에게 `DEADLINE_SOON` 알림 발송 | 안 함 (`Notification`만 insert) | 위험 낮음 |
| (2) 미달 공구 종료 | 마감이 지난 미달 공구를 `FAILED`로 전이하고 `FUNDING_FAILED` 알림 발송 | `status` 변경 | 참여와 충돌 |

(2)가 참여와 충돌하는 이유 :  둘 다 같은 공구 row의 수량을 읽고 상태를 쓰기 때문.
참여는 `currentQuantity`를 올리고, 마지막 1자리면 `status`를 `FINISHED`로 바꾼다.
(2)는 `currentQuantity`를 읽어 미달이면 `status`를 `FAILED`로 바꾼다.
락이 없으면 한쪽이 읽은 값이 상대가 쓴 뒤에는 낡은 값이 된다(ADR-0001이 다룬 "참여(재고 차감)"과 같은 종류의 문제).
이 ADR의 핵심 대상은 (2)이고, (1)은 시나리오 3에서만 다룬다.

**시나리오 1이 검증하는 것**: 참여와 잡 2가 둘 다 마감 체크를 통과하는 좁은 시간 창에서,
마지막 1자리를 두고 누가 먼저 `findByIdForUpdate` 락을 잡느냐다.
두 경로가 같은 row 락을 공유하므로 한쪽이 상태(`FINISHED`/`FAILED`)를 확정하면
나머지 요청은 사유(자리 소진/마감)와 무관하게 동일하게 막힌다.
누가 이기는지는 검증 대상이 아니고, 어느 쪽이 이겨도 불변식(아래 시나리오 1)이 깨지지 않는지만 확인한다.

## 비교 대상 구현

### A. 핵심 동시성 제어 (참여와 FAILED 판정 잡이 동시에 실행)

| 구현 | 방식 | 비고                                                                                                          |
|---|---|-------------------------------------------------------------------------------------------------------------|
| A-1. 단건 비관적 락 재사용 | 락 없는 SELECT로 후보 id 조회 -> id별 `findByIdForUpdate` 재사용, 락 안에서 재검증 후 신규 엔티티 메서드로 전이 | ADR-0001과 동일 패턴, `increaseQuantityAndCloseIfNeeded`와 대칭되는 `closeAsFailedIfDeadlinePassed(referenceTime)` 추가 |
| A-2. 조건부 벌크 UPDATE | `UPDATE ... SET status='FAILED' WHERE status='RECRUITING' AND deadline_at<=:now AND current_quantity<target_quantity` 단일 SQL | `findByIdForUpdate` 재사용 불가, RETURNING 미지원이라 전이 대상 식별이 어려움                                                   |
| A-3. 후보 조회 + 청크 단위 비관적 락 | A-1과 동일하지만 id를 페이지(예: 100건) 단위로 나눠 처리 | Spring Batch chunk 지향 구조에 대응, 대량 처리 시 A-1보다 유리할 가능성 있음                                                      |

### B. 잡 실행 자체의 안전성 (중복/중첩 실행)

| 구현 | 방식 | 비고 |
|---|---|---|
| B-1. 조건부 UPDATE 원자성 + 알림 unique 제약 | 상태 전이는 WHERE절 조건으로 자연히 멱등, `Notification(user_id, goods_funding_id, type)` unique 제약 + insert 전 존재 확인으로 알림 중복만 별도 방어 | 신규 의존성 없음 |
| B-2. Redisson RLock 잡 단위 락 | 잡 시작 시 `lock:batch:goods-funding-settlement` 같은 키로 RLock 획득, 실패 시 skip-and-log |  신규 의존성 필요|
| B-3. Spring Batch JobRepository의 JobInstance 중복 방지 | JobRepository 메타 테이블 기반으로 동일 JobInstance 중복 실행을 예외로 차단 | Job/Step 인프라 전체를 그린필드에서 신규 도입, 이슈 스코프(단순 `@Scheduled` 2개)에 과함 |

## 실험 설계

`AbstractConcurrencyIntegrationTest` 상속 + `ExecutorService`
기반 동시 호출 통합 테스트로 ADR-0001과 동일한 방법론을 따른다. 잡 메서드는 `LocalDateTime.now()`를
내부에서 직접 호출하지 않고 `referenceTime` 파라미터로 주입받도록 설계해 sleep 없이 "마감이 막 지난
순간"을 재현한다.

- **시나리오 1 (참여와 잡이 동시에 실행)**: `targetQuantity=20`, 사전 참여로 `currentQuantity=19`(1자리 남음),
  `deadlineAt=now+10s`(참여 경로는 `OrderService`가 실시간 `LocalDateTime.now()`로 마감을 체크하므로,
  사전 참여·참가자 생성 등 설정 단계가 마감 전에 끝나도록 버퍼를 넉넉히 둔다. 레이스 자체는
  `CountDownLatch`로 동시에 출발하므로 버퍼 크기와 무관하다. 버퍼가 부족했던 사례는 아래
  테스트 환경 트러블슈팅 #3 참고). 참여 그룹(M=50명, 신규 유저, `quantity=1`)과 잡 호출 그룹(K=10회 동시 호출,
  `referenceTime=deadlineAt.plusNanos(1)`)을 동일 `CountDownLatch`로 동시 출발.
  - 측정: 최종 `status`, `currentQuantity`, 참여 성공 카운트, K회 잡 호출 중 실제 전이 발생 횟수,
    `FUNDING_FAILED` Notification 개수.
  - 불변식: `FAILED`면 `currentQuantity<targetQuantity`, `FINISHED`면 `currentQuantity==targetQuantity`,
    `sum(Orders.quantity)==currentQuantity`, 확정 상태(FINISHED/FAILED)는 서로 되돌아가지 않음(단조성),
    두 결과 중 정확히 하나만 성립.
- **시나리오 2 (잡 중복/중첩 실행)**: 사전 참여로 `currentQuantity=19(<20)`, `deadlineAt=now+10s`,
  `referenceTime=deadlineAt.plusSeconds(1)`로 마감 이후를 재현. 동일 잡 메서드를 K=10회 동시 호출.
  - 측정: 10회 중 실제 전이 발생 횟수(=1이어야 함), `FUNDING_FAILED` Notification 개수
    (=참여자 수, 190건이 아닌 19건이어야 함 — B-1의 알림 unique 제약 없이도 안전한지 실측).
  - 구현 확인(코드 리뷰로 사전 확인, 실측으로 재검증): `SettlementNotifier.notifyFundingFailed`는
    참여자별 루프 안에서 `existsBy` 사전체크 + `save()`를 개별 `try-catch(DataIntegrityViolationException)`로
    감싼다. `Notification.id`가 `GenerationType.IDENTITY`라서 `save()` 시점에 INSERT가 즉시 flush되어
    예외가 그 자리에서 동기적으로 발생하므로 개별 catch가 실제로 작동하고(트랜잭션 끝의 일괄 flush까지
    미뤄지지 않음), MySQL/InnoDB는 단일 문장의 제약 위반으로 트랜잭션 전체를 abort시키지 않으므로 같은
    트랜잭션 안에서 나머지 참여자 처리를 계속할 수 있다. 즉 "일부만 처리되고 잡이 중간에 죽는" 실패
    모드는 구조적으로 배제되어 있다는 것이 코드 리뷰 결론이었고, 아래 실측 결과에서 재확인됐다
    (190건이 아닌 19건 — PRE_PARTICIPANTS와 정확히 일치).
- **시나리오 3 (마감임박 알림 잡의 중복 발송 방지)**: 참여자 P명, `deadlineAt`을 임박 임계값 이내로
  세팅. 알림 후보 조회 잡을 K회 동시 호출.
  - 측정: `DEADLINE_SOON` Notification 개수(=P, K회 반복해도 늘어나지 않아야 함).
  - 주의: 잡 1은 `GoodsFunding.status`를 바꾸지 않으므로 A-1/A-2/A-3(참여와 잡이 동시에 실행되는 경우의 구현)의
    차이가 이 시나리오의 결과에 관여하지 않는다. 그래서 A축은 고정하고 B축(B-1/B-2)만 비교하도록 설계했다.
    A x B 전체 조합으로 돌리면 A-1/A-2/A-3에 대해 동일 결과가 나올 실험을 3배 반복하게 된다.

시나리오 1/2는 A-1/A-2 x B-1 조합으로 실행했다. 시나리오 3은 B-1로만 실행했다. B-2/B-3은 실제
구현체를 만들지 않아 실험 대상에서 제외했다(아래 실측 결과 참고). TPS/지연은 참고 지표로만 남기고, 주 지표는 정합성 위반 재현 여부로 한다.

## 실측 결과

Testcontainers MySQL 8.0에서 실행했다. 시나리오 1·2는 `AbstractSettlementConcurrencyExperimentTest`(A-1/A-2 각각 서브클래스),
시나리오 3은 `DeadlineSoonNotificationConcurrencyTest`로 실행했다.
A-3, B-2는 A-1/A-2·B-1만으로 정합성 위반이 재현되지 않아 구현체를 만들지 않고 실험을 종료했다(결정 참고).

### 정합성

| 시나리오 | 대상 | 실행 | 확인 항목 | 결과 |
|---|---|---|---|---|
| 1. 참여와 잡이 동시에 실행 | A-1, A-2 | 각 3회 | `FINISHED`/`FAILED` 중 하나만 확정, `currentQuantity` 불변식 | 위반 없음 |
| 2. 잡 중복 실행 (10회 동시 호출) | A-1, A-2 (B-1) | 각 3회 | 실제 전이 횟수, `FUNDING_FAILED` 알림 수 | 전이 1회, 알림 19건 (참여자 수와 같음) |
| 3. 마감임박 알림 잡 중복 실행 (10회 동시 호출) | B-1 | 참여자 19명, 공구 1건 | `DEADLINE_SOON` 알림 수 | 19건 (참여자 수와 같음) |

시나리오 1·2는 A-1/A-2 두 구현 모두 3회씩 통과했다(총 12회 테스트 실행).
분산락 없이 B-1(조건부 UPDATE의 원자성 + 알림 unique 제약)만으로 이 조건에서 중복 전이와 알림 중복이 발생하지 않았다.

### 지연 (참고 지표)

| 구현 | 시나리오 1 실행 시간 |
|---|---|
| A-1 (비관적 락 재사용) | 1.85초 |
| A-2 (벌크 UPDATE) | 16.2초 |

- 단일 실행 기준 참고값이다. 사전 가설(A-2가 단일 SQL이라 유리)과 반대 결과다.
- 원인은 확인하지 못했다. `bulkCloseAsFailed`의 WHERE 조건(`status`+`deadline_at`+`current_quantity`)에 맞는 인덱스가 없어
  참여 트랜잭션과의 락 경합이 커졌을 가능성을 의심하지만 검증하지 않았다.
- A-2를 채택하지 않으므로 EXPLAIN 분석은 하지 않았고, 필요해지면 후속 이슈에서 조사한다.

### 테스트 환경 트러블슈팅 (동시성 로직과 무관)

| # | 문제 | 수정 |
|---|---|---|
| 1 | `AbstractConcurrencyIntegrationTest`가 `@Container` static 필드를 부모에 선언해서, 서브클래스가 2개 이상이 되자(A-1/A-2) 먼저 끝난 쪽이 컨테이너를 stop시켜 나중 쪽이 연결에 실패했다 | 싱글턴 컨테이너 패턴(`static { mysql.start(); }`, `@Container` 미사용) |
| 2 | 테스트 클래스 간 데이터 정리가 없어 `host1`/`host2` 같은 고정 이메일이 유니크 제약 충돌을 일으켰다 | `@AfterEach`로 FK 순서(orders → notification → goods_funding → user) 정리 추가 |
| 3 | 시나리오 2의 마감 버퍼(`plusSeconds(1)`)가 사전 참여 19회 순차 호출을 끝내기엔 짧아, 환경이 느리면 정상 참여도 "모집이 끝났습니다"로 거절됐다 | 버퍼를 10초로 확대(잡 판정은 `referenceTime` 주입이라 버퍼 크기와 무관) |

### 잡 1 구현 중 발견한 반례 (동시성과 다른 종류)

후보 조회와 실제 발송 사이의 시간차로, 조회 시점엔 `RECRUITING`이었지만 발송 시점엔 이미 `FINISHED`/`FAILED`인 공구에도
"마감임박" 알림이 나갈 수 있었다. 적대적 검증으로 재현했고, `GoodsFundingService`에 `findRecruiting(id)`(상태 재확인 조회)를
추가해 발송 직전에 재검증하도록 수정했다. 재현 시나리오는 `docs/experiments/adversarial-test-log-settlement.md`(마감임박 알림 절) 참고.

## 결정

- **A: A-1(단건 비관적 락 재사용)을 채택한다.** 정합성은 A-1/A-2 동일하게 검증됐지만, 지연 실측에서
  A-2가 8~9배 느렸고(시나리오 1 단일 실행 기준, 위 실측 결과 참고), ADR-0001 패턴 재사용·전이 대상 정확한 식별(알림
  생성용)·jqwik 엔티티 단위 테스트 용이성까지 고려하면 A-2를 선택할 이유가 없다. A-2(벌크 UPDATE)
  구현체(`BulkUpdateSettlementService`)와 그 테스트는 실험 기록 목적으로 남기고 프로덕션 코드로는
  승격하지 않는다. A-3(청크 단위)은 이번 이슈의 데이터 규모에서 필요성이 실측되지 않아 구현하지
  않고, 마감 임박 공구 수가 실제로 커지면 재실험 대상으로 남긴다.
- **B: B-1(분산락 없음 — 조건부 UPDATE의 원자성 + 알림 unique 제약)을 채택한다.** 시나리오 2
  실측(3회 반복 12회 테스트, 잡 중복 전이·알림 중복 0건)으로 별도 분산락 없이 멱등성이
  확인됐다. B-2(Redisson RLock)는 구현하지 않는다 — 인프라 실패 지점(Redis 가용성)을 추가할
  근거가 실측상 없다.
- **Spring Batch(B-3)는 도입하지 않는다.** 이슈 #6이 명시한 "`@Scheduled` 기반, 별도 잡 분리"
  스코프에 Job/Step 인프라 전체 도입은 과설계다. 신규 아키텍처 패턴을 "도입하지 않는" 결정이라
  CLAUDE.md 수정이 필요 없다.

## 트레이드오프

**A-1 채택 (id별 트랜잭션)**
- 감수한 점: 사전 가설과 달리 처리량 열위는 없었다(A-2보다 빨랐다). 다만 id별 트랜잭션 왕복 구조는 남아 있어,
  마감 임박 공구가 테스트 규모(1건)보다 훨씬 많아지면 재검증이 필요하다(A-3 전환 여지).

**A-2 (탈락)**
- 장점: 단일 SQL이라 구현이 단순하다.
- 탈락 이유: 성능 이점이 실측으로 확인되지 않았고(시나리오 1 단일 실행 기준 8~9배 느림, 원인 미확정), 전이 대상을 별도 재조회로
  식별해야 해 깨지기 쉽다. 도메인 로직이 SQL WHERE절에 흩어져 jqwik property test로 엔티티 단위 검증도 어렵다.

**B-1 (채택 시 남는 리스크)**
- 상태 전이·알림 중복은 실측(시나리오 2, 12회 반복)으로 막히는 것을 확인했다.
- 잡이 겹쳐 도는 동안 DB 커넥션/락 경쟁이 이중으로 생기고 운영 로그가 중복되는 문제는 남는다.
  정합성 문제는 아니고 관측성과 자원 낭비 문제다.

**B-2 (채택 시 감수할 점)**
- Redis 가용성이라는 인프라 실패 지점이 추가된다. ADR-0001이 Redisson을 탈락시킨 이유와 같은 비용이다.
- 다만 이번 목적은 동시성 제어가 아니라 중복 실행 방지라서 락 보유 시간이 훨씬 짧고 TPS/지연 민감도도 낮다.
  ADR-0001의 결론을 그대로 쓸 수 없으므로 별도 실측이 필요하다.

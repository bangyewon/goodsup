# ADR 0002: 마감 정산 배치 동시성 제어 전략

- 상태: 확정 (A 트랙: A-1 채택 / B 트랙: B-1 채택, 실측 완료)
- 작성일: 2026-09-15
- 관련 이슈: #6
- 관련 ADR: [0001-concurrency-control-strategy.md](./0001-concurrency-control-strategy.md)

## 배경

마감(`deadlineAt`) 시각에 (1) 참여자가 목표 수량을 채워 `RECRUITING -> FINISHED`로 전이시키는 것과
(2) 마감 정산 배치가 같은 row를 목표 미달로 판단해 `RECRUITING -> FAILED`로 전이시키는 것이
동시에 일어날 수 있다. 두 전이는 상호 배타적이어야 하고, 배치 자체의 중복/중첩 실행도
데이터 정합성(특히 알림 중복 발송)을 해치지 않아야 한다.

이번 ADR을 작성하기 전에 다음 세 가지는 비교 실험 없이 전제로 확정한다:

1. `GoodsFundingStatus`에 `FAILED`를 추가한다(`RECRUITING, FINISHED, FAILED`). `FINISHED`를
   `SUCCESS`로 리네이밍하지는 않는다 — 무관한 범위의 리팩터링이라 이번 diff에서 제외하고,
   `NotificationType.FUNDING_SUCCESS`와의 이름 불일치는 의도적으로 남겨두는 기술 부채로 기록한다.
2. `NotificationType`과의 모델 불일치는 매핑으로 해소한다: `FINISHED -> FUNDING_SUCCESS`,
   `FAILED -> FUNDING_FAILED`. `REFUND_COMPLETED`는 이번 이슈에서 발행하지 않는다.
3. 환불 로직은 이번 이슈 범위에서 제외한다. `Orders`에 `paymentStatus`가 없고 결제 도메인이
   구현되지 않아 환불할 대상 데이터 자체가 없다. 후속 이슈로 분리한다.

마감임박 알림(잡 1)은 `GoodsFunding` 상태를 바꾸지 않고 `Notification`만 insert하므로 경합
위험이 낮다. 반면 마감 정산(잡 2)은 `GoodsFunding.status`를 변경하므로 ADR-0001이 다룬
"참여(재고 차감)"과 동일한 row를 두고 경합한다 — 이 ADR의 핵심 대상은 잡 2다.

**경합 정책(확정, 코드 기준)**: `OrderService.participateGoodsFunding`(line 35-36)은 이미
`!goodsFunding.getDeadlineAt().isAfter(LocalDateTime.now())`이면 `RECRUITING_CLOSED`를 던져
마감을 넘긴 참여를 실시간 기준으로 차단한다. 즉 "마감을 넘긴 참여는 항상 막는다"가 이미 구현된
정책이며, 이번 ADR에서 새로 결정할 사항이 아니다. 시나리오 1이 실제로 검증하는 것은 이 정책의
존재 여부가 아니라, **양쪽 다 마감 체크를 통과할 수 있는 좁은 시간 창 안에서 마지막 1자리를 두고
참여와 배치 중 어느 쪽이 먼저 `findByIdForUpdate` 락을 잡느냐**이다 — 두 경로가 같은 row 락을
공유하므로 한쪽이 상태를 확정(FINISHED/FAILED)시키면 나머지 요청은 사유(자리 소진/마감)와
무관하게 동일하게 막힌다. 따라서 "누가 이기는가"는 검증 대상이 아니고, 결과가 어느 쪽이든
불변식(아래 시나리오 1)이 깨지지 않는지만 확인한다.

## 비교 대상 구현

### A. 핵심 동시성 제어 (참여 vs 배치 FAILED 판정 경합)

| 구현 | 방식 | 비고 |
|---|---|---|
| A-1. 단건 비관적 락 재사용 | 락 없는 SELECT로 후보 id 조회 -> id별 `findByIdForUpdate` 재사용, 락 안에서 재검증 후 신규 엔티티 메서드로 전이 | ADR-0001과 동일 패턴, `increaseQuantityAndCloseIfNeeded`와 대칭되는 `closeAsFailedIfDeadlinePassed(referenceTime)` 추가 |
| A-2. 조건부 벌크 UPDATE | `UPDATE ... SET status='FAILED' WHERE status='RECRUITING' AND deadline_at<=:now AND current_quantity<target_quantity` 단일 SQL | `findByIdForUpdate` 재사용 불가, RETURNING 미지원이라 전이 대상 식별이 어려움 |
| A-3. 후보 조회 + 청크 단위 비관적 락 | A-1과 동일하지만 id를 페이지(예: 100건) 단위로 나눠 처리 | Spring Batch chunk 지향 구조에 대응, 대량 처리 시 A-1보다 유리할 가능성 |

### B. 배치 실행 자체의 안전성 (중복/중첩 실행)

| 구현 | 방식 | 비고 |
|---|---|---|
| B-1. 조건부 UPDATE 원자성 + 알림 unique 제약 | 상태 전이는 WHERE절 조건으로 자연히 멱등, `Notification(user_id, goods_funding_id, type)` unique 제약 + insert 전 존재 확인으로 알림 중복만 별도 방어 | 신규 의존성 없음 |
| B-2. Redisson RLock 배치 단위 락 | 배치 시작 시 `lock:batch:goods-funding-settlement` 같은 키로 RLock 획득, 실패 시 skip-and-log | `org.redisson:redisson-spring-boot-starter` 신규 의존성 필요(현재 build.gradle.kts에 없음) -> CLAUDE.md 원칙 구체화 선행 필요 |
| B-3. Spring Batch JobRepository의 JobInstance 중복 방지 | JobRepository 메타 테이블 기반으로 동일 JobInstance 중복 실행을 예외로 차단 | Job/Step 인프라 전체를 그린필드에서 신규 도입, 이슈 스코프(단순 `@Scheduled` 2개)에 과함 |

## 실험 설계

`AbstractConcurrencyIntegrationTest`(Testcontainers MySQL 8.0) 상속 + `ExecutorService`/`CountDownLatch`
기반 동시 호출 통합 테스트로 ADR-0001과 동일한 방법론을 따른다. 배치 메서드는 `LocalDateTime.now()`를
내부에서 직접 호출하지 않고 `referenceTime` 파라미터로 주입받도록 설계해 sleep 없이 "마감이 막 지난
순간"을 결정론적으로 재현한다.

- **시나리오 1 (참여 vs 배치 경합)**: `targetQuantity=20`, 사전 참여로 `currentQuantity=19`(1자리 남음),
  `deadlineAt=now+10s`(참여 경로는 `OrderService`가 실시간 `LocalDateTime.now()`로 마감을 체크하므로,
  사전 참여·레이스 참가자 생성 등 설정 단계의 순차 DB 왕복이 실제로 이 버퍼를 잠식한다 — 처음
  `+300ms`로 설계했다가 실측 중 너무 촉박해 정상 동작까지 거절되는 문제가 재현되어 10초로 확대했다,
  아래 실측 결과 버그 #3 참고). 참여 그룹(M=50명, 신규 유저, `quantity=1`)과 배치 그룹(K=10회 동시 호출,
  `referenceTime=deadlineAt.plusNanos(1)`)을 동일 `CountDownLatch`로 동시 출발.
  - 측정: 최종 `status`, `currentQuantity`, 참여 성공 카운트, K회 배치 호출 중 실제 전이 발생 횟수,
    `FUNDING_FAILED` Notification 개수.
  - 불변식: `FAILED`면 `currentQuantity<targetQuantity`, `FINISHED`면 `currentQuantity==targetQuantity`,
    `sum(Orders.quantity)==currentQuantity`, 확정 상태(FINISHED/FAILED)는 서로 되돌아가지 않음(단조성),
    두 결과 중 정확히 하나만 성립.
- **시나리오 2 (배치 중복/중첩 실행 idempotency)**: 참여 없이 `currentQuantity=19(<20)`,
  `deadlineAt`은 과거로 세팅. 동일 배치 메서드를 K=10회 동시 호출.
  - 측정: 10회 중 실제 전이 발생 횟수(=1이어야 함), `FUNDING_FAILED` Notification 개수
    (=참여자 수, 190건이 아닌 19건이어야 함 — B-1의 알림 unique 제약 없이도 안전한지 실측).
  - 구현 확인(코드 리뷰로 사전 확인, 실측으로 재검증): `SettlementNotifier.notifyFundingFailed`는
    참여자별 루프 안에서 `existsBy` 사전체크 + `save()`를 개별 `try-catch(DataIntegrityViolationException)`로
    감싼다. `Notification.id`가 `GenerationType.IDENTITY`라서 `save()` 시점에 INSERT가 즉시 flush되어
    예외가 그 자리에서 동기적으로 발생하므로 개별 catch가 실제로 작동하고(트랜잭션 끝의 일괄 flush까지
    미뤄지지 않음), MySQL/InnoDB는 단일 문장의 제약 위반으로 트랜잭션 전체를 abort시키지 않으므로 같은
    트랜잭션 안에서 나머지 참여자 처리를 계속할 수 있다. 즉 "일부만 처리되고 배치가 중간에 죽는" 실패
    모드는 구조적으로 배제되어 있다는 것이 코드 리뷰 결론이었고, 아래 실측 결과에서 재확인됐다
    (190건이 아닌 19건 — PRE_PARTICIPANTS와 정확히 일치).
- **시나리오 3 (마감임박 알림 잡의 중복 발송 방지)**: 참여자 P명, `deadlineAt`을 임박 임계값 이내로
  세팅. 알림 후보 조회 잡을 K회 동시 호출.
  - 측정: `DEADLINE_SOON` Notification 개수(=P, K회 반복해도 늘어나지 않아야 함).
  - 주의: 잡 1은 `GoodsFunding.status`를 바꾸지 않으므로 A-1/A-2/A-3(참여 vs 배치 경합 구현)의
    차이가 이 시나리오의 결과에 관여하지 않는다. 이 시나리오만 **B-1/B-2 2가지로만** 반복 실행한다
    (A축 고정). A x B 전체 조합으로 돌리면 A-1/A-2/A-3에 대해 동일 결과가 나올 실험을 3배 반복하게 된다.
    잡 1 자체가 아직 구현되지 않아(아래 실측 결과 참고) 이 시나리오는 이번 실험에서 실행하지
    못했다 — 후속 작업으로 남긴다.

시나리오 1/2는 A-1/A-2 x B-1 조합으로(B-2/B-3은 실제 구현체를 만들지 않아 대상에서 제외, 아래
실측 결과 참고), 시나리오 3은 잡 1 구현 이후 B-1/B-2만으로 반복 실행해 재현 빈도(N회 중 위반
횟수)를 기록한다. TPS/지연은 참고 지표로만 남기고, 주 지표는 정합성 위반 재현 여부로 한다.

## 실측 결과

`AbstractSettlementConcurrencyExperimentTest`(시나리오 1·2, A-1/A-2 각각 서브클래스로 실측)를
Testcontainers MySQL 8.0 기준으로 실행했다. A-3, B-2는 A-1/A-2·B-1만으로 정합성 위반이
재현되지 않아 이번 이슈 스코프에서는 실제 구현체를 만들지 않고 실험을 종료했다(아래 결정 참고).

- **정합성**: A-1, A-2 두 후보 모두 시나리오 1·2를 3회 연속 반복 실행(총 12회 테스트 실행)해서
  전부 통과했다. 시나리오 1에서 `FINISHED`/`FAILED` 중 정확히 하나만 확정되고 `currentQuantity`
  불변식이 항상 성립했으며, 시나리오 2에서 배치 10회 동시 호출 중 실제 전이는 항상 1회,
  `FUNDING_FAILED` 알림도 항상 참여자 수(19건)와 정확히 일치했다 — B-1(조건부 UPDATE의 원자성 +
  알림 unique 제약, 별도 분산락 없음)만으로 배치 중복/중첩 실행에 대한 멱등성이 실측으로 확인됐다.
- **지연(참고 지표)**: 시나리오 1 단일 테스트 실행 시간이 A-1(비관적 락 재사용) 1.85초 대비
  A-2(벌크 UPDATE) 16.2초로, **A-2가 오히려 8~9배 느렸다** — 애초 가설("A-2가 단일 SQL로 처리량에서
  유리할 것")과 반대되는 결과다. 원인은 미확정이나, `bulkCloseAsFailed`의 WHERE 조건
  (`status`+`deadline_at`+`current_quantity`)에 대응하는 인덱스가 없어 조건부 UPDATE가 참여
  트랜잭션들과의 락 경합에서 더 넓은 범위를 잠그거나 재시도 비용이 커졌을 가능성을 의심한다.
  이 이슈 스코프에서는 A-2를 채택하지 않으므로 근본 원인 규명(EXPLAIN 분석)은 더 진행하지 않고,
  후속 이슈로 필요 시에만 재조사하기로 한다.
- **실험 과정에서 발견한 인프라 버그 3건** (동시성 로직 자체와 무관, 테스트 하네스 문제):
  1. `AbstractConcurrencyIntegrationTest`가 `@Container` static 필드를 부모 클래스에 선언해서,
     같은 부모를 상속하는 서브클래스가 2개 이상이 되자(A-1/A-2) 먼저 끝난 서브클래스가 컨테이너를
     stop시켜 나중 서브클래스가 죽은 컨테이너에 연결 시도 → 연결 실패로 오진될 뻔했다. 싱글턴
     컨테이너 패턴(`static { mysql.start(); }`, `@Container` 미사용)으로 수정.
  2. 테스트 클래스 간 데이터 정리가 없어 `host1`/`host2` 등 고정 이메일이 클래스 간에 유니크 제약
     충돌을 일으켰다 → `AbstractSettlementConcurrencyExperimentTest`에 `@AfterEach`로
     FK 순서(orders → notification → goods_funding → user) 정리 추가.
  3. 시나리오 2의 마감 버퍼(`plusSeconds(1)`)가 사전 참여 19회 순차 호출을 실제 벽시계 기준으로
     끝내기엔 너무 촉박해 환경이 느릴 때 정상 참여도 "모집이 끝났습니다"로 거절됐다 → 버퍼를
     10초로 확대(배치 판정 자체는 `referenceTime` 주입이라 버퍼 크기와 무관하게 결정론적).
- **시나리오 3(잡 1 중복 발송 방지, 후속 작업으로 추가 실행)**: 잡 1(마감임박 알림, `NotificationService`
  /`DeadlineSoonNotificationScheduler`)을 구현하면서 `DeadlineSoonNotificationConcurrencyTest`로
  실행했다. 참여자 19명이 있는 임박 공구 1건에 배치를 10회 동시 호출한 결과 `DEADLINE_SOON` 알림은
  항상 정확히 19건(참여자 수와 일치, 190건 아님)이었다 — B-1 패턴(별도 분산락 없이 `existsBy` +
  유니크 제약)이 잡 2뿐 아니라 잡 1의 중복 실행 방지에도 그대로 유효함을 실측으로 확인했다. 이로써
  "잡 1이 아직 구현되지 않아 실행하지 못했다"고 남겨뒀던 항목을 닫는다.
- **잡 1 구현 중 추가로 발견한 반례(동시성과는 다른 종류)**: 후보 조회와 실제 발송 사이의 시간차로
  인해, 조회 시점엔 `RECRUITING`이었지만 발송 시점엔 이미 `FINISHED`/`FAILED`로 바뀐 공구에도
  "마감임박" 알림이 나갈 수 있는 결함을 적대적 검증으로 발견·재현했다. `GoodsFundingService`에
  `findRecruiting(id)`(상태 재확인 조회)를 추가해 발송 직전 재검증하도록 수정했다. 상세 내용과
  재현 시나리오는 `docs/experiments/adversarial-test-log.md`(이슈 #6 잡 1 절) 참고.

## 결정

- **A: A-1(단건 비관적 락 재사용)을 채택한다.** 정합성은 A-1/A-2 동일하게 검증됐지만, 지연 실측에서
  A-2가 오히려 8~9배 느렸고(위 실측 결과 참고), ADR-0001 패턴 재사용·전이 대상 정확한 식별(알림
  생성용)·jqwik 엔티티 단위 테스트 용이성까지 고려하면 A-2를 선택할 이유가 없다. A-2(벌크 UPDATE)
  구현체(`BulkUpdateSettlementService`)와 그 테스트는 실험 기록 목적으로 남기고 프로덕션 코드로는
  승격하지 않는다. A-3(청크 단위)은 이번 이슈의 데이터 규모에서 필요성이 실측되지 않아 구현하지
  않고, 마감 임박 공구 수가 실제로 커지면 재실험 대상으로 남긴다.
- **B: B-1(분산락 없음 — 조건부 UPDATE의 원자성 + 알림 unique 제약)을 채택한다.** 시나리오 2
  실측(3회 반복 12회 테스트, 배치 중복 전이·알림 중복 0건)으로 별도 분산락 없이 멱등성이
  확인됐다. B-2(Redisson RLock)는 구현하지 않는다 — 인프라 실패 지점(Redis 가용성)을 추가할
  근거가 실측상 없다.
- **Spring Batch(B-3)는 도입하지 않는다.** 이슈 #6이 명시한 "`@Scheduled` 기반, 별도 잡 분리"
  스코프에 Job/Step 인프라 전체 도입은 과설계다. 신규 아키텍처 패턴을 "도입하지 않는" 결정이라
  CLAUDE.md 수정이 필요 없다.

## 트레이드오프

- **A-1 채택 시 포기하는 것**: 사전 가설과 달리 실측에서 처리량 열위는 없었다(오히려 A-2보다
  빨랐다). 다만 id별 트랜잭션 왕복 구조 자체는 남아있어, 마감 임박 공구 수가 지금 테스트 규모(1건)
  보다 훨씬 커지면 재검증이 필요하다(A-3로 전환 여지 남김).
- **A-2를 탈락시킬 경우 포기하는 것**: 단일 SQL이 주는 구현 단순성. 성능 이점은 실측상 확인되지
  않았고(오히려 8~9배 느림, 원인 미확정) 오히려 전이 대상을 별도 재조회로 식별해야 하는 fragility,
  도메인 로직이 SQL WHERE절에 흩어져 jqwik property test로 엔티티 단위 검증이 어려워지는 점이
  더 크다고 판단.
- **B-1 채택 시 남는 리스크**: 상태 전이·알림 중복은 실측(시나리오 2, 12회 반복)으로 확인됐지만,
  배치가 겹쳐 도는 동안 DB 커넥션/락 경쟁이 이중으로 발생하고 운영 로그가 중복 남는 문제 자체는
  완전히 막지 못한다(정합성 문제는 아니고 운영 관측성/자원 낭비 문제로 남는다).
- **B-2를 채택할 경우 포기하는 것**: 인프라 실패 지점 추가(Redis 가용성)라는 점에서 ADR-0001이
  Redisson을 탈락시킨 이유와 동일한 비용을 다시 지게 된다. 다만 ADR-0001의 대상(재고 차감)과
  달리 이번엔 "동시성 제어"가 아니라 "중복 실행 방지"가 목적이라 요구되는 락 보유 시간이 훨씬
  짧고, TPS/지연 민감도가 낮다는 점에서 ADR-0001의 결론을 그대로 재사용할 수 없다 — 별도 실측 필요.

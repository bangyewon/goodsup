# 적대적 동시성 테스트 로그 — 미달 공구 종료·마감임박 알림 잡 (이슈 #6)

[인덱스로 돌아가기](./adversarial-test-log.md)

## 미달 공구 종료 잡 (ADR-0002)

대상 로직: 미달 공구 종료 잡 후보 A-1(`PessimisticLockSettlementBatch`)/A-2(`BulkUpdateSettlementService`),
B-1(분산락 없음, 알림 unique 제약)의 상태 전이·중복 실행 방지 (ADR-0002, 이슈 #6).

| # | 시나리오 | 실제 재현 여부 | 원인 | 수정 내용 | 관련 커밋 |
|---|---|---|---|---|---|
| 1 | 참여 vs 종료 잡이 마지막 한 자리를 두고 동시 경합하면 `FINISHED`/`FAILED`가 동시에 확정되거나 재고 불변식이 깨질 수 있다 | 재현 안 됨 (A-1/A-2 둘 다, 3회 반복 총 12회 실행) | A-1은 ADR-0001과 동일한 `findByIdForUpdate` 비관적 락으로, A-2는 조건부 UPDATE의 원자성으로 각각 직렬화됨 | - | - |
| 2 | 미달 공구 종료 잡이 중첩·중복 실행되면 상태 전이나 `FUNDING_FAILED` 알림이 중복 발생할 수 있다 | 재현 안 됨 (A-1/A-2 둘 다, B-1만으로) | 조건부 UPDATE(`WHERE status='RECRUITING'`)는 이미 `FAILED`인 공구를 다시 바꾸지 않고, `Notification` unique 제약(`uk_notification_user_funding_type`)과 insert 전 존재 확인이 알림 중복을 막음 | - | - |
| 3 | (테스트 환경 자체의 결함) 여러 종료 잡 후보를 같은 부모 `AbstractConcurrencyIntegrationTest`를 상속해 실측 비교하면, Testcontainers `@Container` static 필드가 먼저 끝난 서브클래스에서 stop되어 나중 서브클래스가 죽은 컨테이너에 연결 시도 | **재현됨** | `@Container`(Testcontainers JUnit5 확장)로 선언한 static 필드는 부모 클래스가 소유해 여러 서브클래스가 공유하는데, 확장이 "먼저 시작한 서브클래스"의 afterAll에서 stop시킴 | 싱글턴 컨테이너 패턴(`static { mysql.start(); }`, `@Container` 제거)으로 전환 | 이번 커밋(AbstractConcurrencyIntegrationTest) |
| 4 | (테스트 환경 자체의 결함) 여러 후보 테스트 클래스가 같은 DB를 공유하는데 고정된 이메일(`host1`, `host2` 등)을 재사용하면 클래스 간 유니크 제약 충돌이 날 수 있다 | **재현됨** | 정리(cleanup) 없이 동일 이메일을 여러 테스트 클래스가 순서대로 insert | `AbstractSettlementConcurrencyExperimentTest`에 `@AfterEach`로 FK 순서(orders→notification→goods_funding→user) 정리 추가 | 이번 커밋 |
| 5 | (테스트 환경 자체의 결함) 사전 참여 단계의 마감 버퍼가 너무 촉박하면 환경이 느릴 때 정상 참여도 거절될 수 있다 | **재현됨** | 시나리오 2의 `deadlineAt = now + 1초` 버퍼로 순차 참여 19회를 끝내기엔 부족 | 버퍼를 10초로 확대 | 이번 커밋 |

### 회고 (#6, 미달 공구 종료 잡)

- **예상대로였던 부분**: 동시성 제어 로직(A-1/A-2/B-1)에서는 반례가 재현되지 않았다. ADR-0001에서 쓴
  "명시적 락으로 보호" 원칙을 그대로 적용한 결과로 본다.
- **예상과 달랐던 부분**: 테스트 환경 결함 3건(#3~#5)이 나왔다. 프로덕션 동시성 버그가 아니라, 종료 잡
  후보를 여러 개 나란히 실측하면서 처음 드러난 문제다(그전에는 후보가 하나뿐이라 컨테이너 공유·데이터
  정리 문제가 없었다). A-2(벌크 UPDATE)는 정합성은 통과했지만 지연이 A-1보다 8~9배 느렸다. 단일 SQL이
  더 빠를 거라는 사전 가설과 반대였다(ADR-0002 실측 결과 절 참고).

## 마감임박 알림 (이슈 #6 잡 1)

대상 로직: `NotificationService.notifyDeadlineSoon`/`DeadlineSoonNotificationScheduler`(이슈 #6 잡 1, 마감임박 알림).

| # | 시나리오 | 실제 재현 여부 | 원인 | 수정 내용 | 관련 커밋 |
|---|---|---|---|---|---|
| 1 | 스케줄러가 임박 대상 id를 조회한 시점엔 `RECRUITING`이었지만, 그 이후 `NotificationService.notifyDeadlineSoon`이 실제로 실행되는 시점 사이에(참여로 목표 달성 → `FINISHED`, 또는 종료 잡 → `FAILED`) 상태가 바뀌면, 이미 끝난 공구에도 여전히 "마감임박" 알림이 나간다 | **재현됨** | `notifyDeadlineSoon`이 `GoodsFunding`을 FK 참조용 프록시(`getReferenceById`, 쿼리 없음)로만 사용하고 현재 상태를 재확인하지 않음 — 후보 조회와 발송 사이의 시간차를 고려하지 않은 설계 누락 | `GoodsFundingService.getReference`를 제거하고 `findRecruiting(id)`(실제 조회 + `status == RECRUITING` 필터)로 교체, `notifyDeadlineSoon`은 이 조회 결과가 없으면(이미 RECRUITING이 아니면) 즉시 0건 반환하고 종료 | 이번 커밋(GoodsFundingService/NotificationService/NotificationServiceTest) |
| 2 | 잡이 중첩·중복 실행되면 `DEADLINE_SOON` 알림이 참여자 수보다 많이 발송될 수 있다 | 재현 안 됨 (통합 테스트로 실측, ADR-0002가 미뤄둔 시나리오 3) | `existsBy` 사전체크 + `Notification` 유니크 제약(`uk_notification_user_funding_type`) + 개별 `DataIntegrityViolationException` catch 조합(B-1 패턴)이 잡 1에서도 중복을 막음을 실측으로 확인 — 참여자 19명, 동시 호출 10회에서도 알림은 19건 | - | - |
| 3 | `threshold-hours` 설정값이 0 이하이거나 매우 크면 어떻게 되는가 | 해당 없음 (데이터 정합성 문제 아님) | `now.plusHours(threshold)`가 `now`보다 이전이 되면 BETWEEN 조건이 항상 거짓이 되어 후보가 조회되지 않을 뿐, 예외나 오동작은 없음 — 운영 설정값의 문제이지 로직 결함이 아님 | - | - |
| 4 | 스케줄러 조회 이후 대상 공구가 삭제되면 어떻게 되는가 | 해당 없음 | 현재 코드베이스에 `GoodsFunding` 삭제 기능이 없음(`grep` 확인) — 지금은 발생하지 않는 시나리오 | - | - |

### 회고 (#6, 잡 1)

- **예상과 달랐던 부분**: 시나리오 1은 동시성 문제가 아니었다. 후보 조회와 실제 발송이 서로 다른
  트랜잭션이라, 그 사이에 상태가 바뀌어도 알림이 나가는 문제였다. 같은 잡이라도 "동시에 여러 번 도는
  경우"와 "한 번 도는 동안 시간이 흐르는 경우"를 따로 검토해야 한다.
- **예상대로였던 부분**: 시나리오 2(중복 실행 방지)는 ADR-0002가 잡 1이 없어 실행하지 못하고 남겨둔
  것이었다. 이번에 실행해 보니 B-1 패턴이 잡 1에도 통했다(참여자 19명, 동시 호출 10회, 알림 19건).

## GoodsFunding 상태 전이 불변식 — jqwik property test (이슈 #6 잡 2)

대상 로직: `GoodsFunding.increaseQuantityAndCloseIfNeeded`/`closeAsFailedIfDeadlinePassed`
(ADR-0002 A-1을 `GoodsFundingService`/`DeadlineSettlementScheduler`로 프로덕션 배선하는 과정,
이슈 #6 잡 2). 사람이 시나리오를 미리 떠올려 요청한 것이 아니라, CLAUDE.md가 요구하는 jqwik
stateful property test(참여 명령 + 종료 명령을 섞은 무작위 커맨드 시퀀스)를 작성해 실행하는 중
jqwik이 자동으로 축소(shrink)한 반례로 발견됐다.

| # | 시나리오 | 실제 재현 여부 | 원인 | 수정 내용 | 관련 커밋 |
|---|---|---|---|---|---|
| 1 | 종료 잡이 먼저 `FAILED`로 확정한 뒤, 그 시점 이후에도(예: 지연된 재시도, 또는 향후 새 호출 경로가 상태 체크 없이 호출하는 경우) `increaseQuantityAndCloseIfNeeded`가 호출되면 `remainingQuantity`만 보고 통과시켜 수량이 계속 증가하고, 목표에 도달하면 `FAILED -> FINISHED`로 재전이한다 | **재현됨** (jqwik 축소 결과: `commands=[0(정산), 10, 15, 15, 15, 15, 15, 15](참여)` — 정산으로 FAILED 확정 후 참여 누적으로 FINISHED 재전이) | `increaseQuantityAndCloseIfNeeded`가 `status`를 확인하지 않고 `targetQuantity - currentQuantity`만으로 수량 증가 가능 여부를 판단함. 현재 유일한 호출자인 `OrderService.participateGoodsFunding`은 호출 전 `status == RECRUITING`을 확인해서 실제로는 막혀 있었지만, 그 가드는 호출자 쪽에만 있고 엔티티 자신은 "정산 후 상태 전이는 되돌아가지 않는다"는 불변식을 스스로 지키지 못했다 | `increaseQuantityAndCloseIfNeeded` 시작부에 `status != RECRUITING`이면 `GoodsException(RECRUITING_CLOSED)`를 던지는 가드 추가(`closeAsFailedIfDeadlinePassed`가 이미 갖고 있던 가드와 같은 방식). 회귀 테스트를 jqwik 반례를 축소한 그대로 `GoodsFundingTest`에 example-based로 고정 | 이번 커밋(GoodsFunding/GoodsFundingTest/GoodsFundingConcurrencyInvariantPropertyTest) |

### 회고 (#6, 잡 2 프로덕션 배선)

- **예상과 달랐던 부분**: 이 반례는 동시성이 아니라 단일 스레드 안에서의 순서 문제였다. `ExecutorService`
  기반 통합 테스트(`DeadlineSettlementConcurrencyTest`)는 "참여와 종료 잡이 동시에 오는 경우"만 다뤄서
  "종료 잡 이후에 참여가 뒤늦게 오는 경우"는 놓쳤다. jqwik property test로 해당 부분을 채웠다.
- **지금은 문제가 없는데도 고친 이유**: 현재 호출 경로(`OrderService`)는 호출 전에 `status`를 확인해서
  이 버그가 실제로 나지 않는다. 그래도 엔티티가 스스로 지키도록 고쳤다. 나중에 관리자 도구 같은 새
  호출자가 생겼을 때 매번 호출자가 `status`를 확인해야 한다는 암묵적 전제를 없애기 위함이었다.

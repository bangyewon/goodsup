# 적대적 동시성 테스트 로그 — 마감 정산·마감임박 알림 배치 (이슈 #6)

[인덱스로 돌아가기](./adversarial-test-log.md)

## 마감 정산 배치 (ADR-0002)

대상 로직: 마감 정산 배치 후보 A-1(`PessimisticLockSettlementBatch`)/A-2(`BulkUpdateSettlementService`),
B-1(분산락 없음, 알림 unique 제약)의 상태 전이·중복 실행 방지 (ADR-0002, 이슈 #6).

| # | 시나리오 | 실제 재현 여부 | 원인 | 수정 내용 | 관련 커밋 |
|---|---|---|---|---|---|
| 1 | 참여 vs 배치가 마지막 한 자리를 두고 동시 경합하면 `FINISHED`/`FAILED`가 동시에 확정되거나 재고 불변식이 깨질 수 있다 | 재현 안 됨 (A-1/A-2 둘 다, 3회 반복 총 12회 실행) | A-1은 ADR-0001과 동일한 `findByIdForUpdate` 비관적 락으로, A-2는 조건부 UPDATE의 원자성으로 각각 직렬화됨 | - | - |
| 2 | 마감 정산 배치가 중첩·중복 실행되면 상태 전이나 `FUNDING_FAILED` 알림이 중복 발생할 수 있다 | 재현 안 됨 (A-1/A-2 둘 다, B-1만으로) | 조건부 UPDATE(`WHERE status='RECRUITING'`)가 자연히 멱등이고, `Notification` unique 제약(`uk_notification_user_funding_type`) + insert 전 존재 확인이 이중 방어선 역할을 함 | - | - |
| 3 | (테스트 하네스 자체의 결함) 여러 정산 후보를 같은 부모 `AbstractConcurrencyIntegrationTest`를 상속해 실측 비교하면, Testcontainers `@Container` static 필드가 먼저 끝난 서브클래스에서 stop되어 나중 서브클래스가 죽은 컨테이너에 연결 시도 | **재현됨** | `@Container`(Testcontainers JUnit5 확장)로 선언한 static 필드는 부모 클래스가 소유해 여러 서브클래스가 공유하는데, 확장이 "먼저 시작한 서브클래스"의 afterAll에서 stop시킴 | 싱글턴 컨테이너 패턴(`static { mysql.start(); }`, `@Container` 제거)으로 전환 | 이번 커밋(AbstractConcurrencyIntegrationTest) |
| 4 | (테스트 하네스 자체의 결함) 여러 후보 테스트 클래스가 같은 DB를 공유하는데 고정된 이메일(`host1`, `host2` 등)을 재사용하면 클래스 간 유니크 제약 충돌이 날 수 있다 | **재현됨** | 정리(cleanup) 없이 동일 이메일을 여러 테스트 클래스가 순서대로 insert | `AbstractSettlementConcurrencyExperimentTest`에 `@AfterEach`로 FK 순서(orders→notification→goods_funding→user) 정리 추가 | 이번 커밋 |
| 5 | (테스트 하네스 자체의 결함) 사전 참여 단계의 마감 버퍼가 너무 촉박하면 환경이 느릴 때 정상 참여도 거절될 수 있다 | **재현됨** | 시나리오 2의 `deadlineAt = now + 1초` 버퍼로 순차 참여 19회를 끝내기엔 부족 | 버퍼를 10초로 확대 | 이번 커밋 |

### 회고 (#6, 마감 정산 배치)

- 순수 동시성 제어 로직(A-1/A-2/B-1)에서는 반례가 하나도 재현되지 않았다 — ADR-0001에서 검증된
  "명시적 락으로 보호"라는 원칙을 그대로 재사용한 결과로 해석한다.
- 대신 검증 과정에서 테스트 하네스 자체의 결함 3건(#3~#5)을 찾았다. 이들은 실제 프로덕션 동시성
  버그가 아니라 "여러 후보를 나란히 실측 비교"하는 이번 ADR 특유의 작업 방식에서 처음 드러난
  문제였다(#5 이전까지는 정산 후보가 하나뿐이라 컨테이너 공유·데이터 정리 문제가 없었음). 실측
  비교 자체가 새로운 종류의 반례(테스트 인프라 버그)를 드러낼 수 있다는 점을 보여준다.
- A-2(벌크 UPDATE)가 정합성은 통과했지만 지연이 A-1 대비 8~9배 느리게 측정된 것은 사전 가설과
  반대되는 결과였다(ADR-0002 실측 결과 절 참고). "직관적으로 빠를 것 같은 단일 SQL"이 실측 없이는
  근거가 될 수 없다는 CLAUDE.md 원칙을 다시 한번 확인시켜준 사례다.

## 마감임박 알림 (이슈 #6 잡 1)

대상 로직: `NotificationService.notifyDeadlineSoon`/`DeadlineSoonNotificationScheduler`(이슈 #6 잡 1, 마감임박 알림).

| # | 시나리오 | 실제 재현 여부 | 원인 | 수정 내용 | 관련 커밋 |
|---|---|---|---|---|---|
| 1 | 스케줄러가 임박 대상 id를 조회한 시점엔 `RECRUITING`이었지만, 그 이후 `NotificationService.notifyDeadlineSoon`이 실제로 실행되는 시점 사이에(참여로 목표 달성 → `FINISHED`, 또는 배치 정산 → `FAILED`) 상태가 바뀌면, 이미 끝난 공구에도 여전히 "마감임박" 알림이 나간다 | **재현됨** | `notifyDeadlineSoon`이 `GoodsFunding`을 FK 참조용 프록시(`getReferenceById`, 쿼리 없음)로만 사용하고 현재 상태를 재확인하지 않음 — 후보 조회와 발송 사이의 시간차를 고려하지 않은 설계 누락 | `GoodsFundingService.getReference`를 제거하고 `findRecruiting(id)`(실제 조회 + `status == RECRUITING` 필터)로 교체, `notifyDeadlineSoon`은 이 조회 결과가 없으면(이미 RECRUITING이 아니면) 즉시 0건 반환하고 종료 | 이번 커밋(GoodsFundingService/NotificationService/NotificationServiceTest) |
| 2 | 배치가 중첩·중복 실행되면 `DEADLINE_SOON` 알림이 참여자 수보다 많이 발송될 수 있다 | 재현 안 됨 (통합 테스트로 실측, ADR-0002가 미뤄둔 시나리오 3) | `existsBy` 사전체크 + `Notification` 유니크 제약(`uk_notification_user_funding_type`) + 개별 `DataIntegrityViolationException` catch 조합(B-1 패턴)이 잡 1에도 동일하게 유효함을 실측 확인 — 참여자 19명, 동시 호출 10회에서도 알림은 정확히 19건 | - | - |
| 3 | `threshold-hours` 설정값이 0 이하이거나 매우 크면 어떻게 되는가 | 해당 없음 (데이터 정합성 문제 아님) | `now.plusHours(threshold)`가 `now`보다 이전이 되면 BETWEEN 조건이 항상 거짓이 되어 후보가 조회되지 않을 뿐, 예외나 오동작은 없음 — 운영 설정값의 문제이지 로직 결함이 아님 | - | - |
| 4 | 스케줄러 조회 이후 대상 공구가 삭제되면 어떻게 되는가 | 해당 없음 | 현재 코드베이스에 `GoodsFunding` 삭제 기능 자체가 없음(`grep` 확인) — 발생 불가능한 시나리오 | - | - |

### 회고 (#6, 잡 1)

- 시나리오 1은 동시성 타이밍 문제가 아니라 "두 개의 분리된 트랜잭션(후보 조회 vs 실제 발송) 사이의 시간차"에서 나온 일반적인 stale-read 문제였다 — ADR-0002가 이미 검증한 "동시 경합"과는 다른 종류의 반례였다는 점에서, 같은 배치라도 "동시에 여러 번 도는 경우"와 "한 번 도는 동안 시간이 흐르는 경우"를 별도로 검토해야 한다는 교훈을 남긴다.
- 시나리오 2(중복 실행 방지)는 ADR-0002가 "잡 1이 아직 구현되지 않아 실행하지 못했다"고 명시적으로 남겨뒀던 후속 작업이었다 — 이번 구현과 함께 실제로 실행해 B-1 패턴이 잡 1에도 유효함을 실측으로 닫았다.

## GoodsFunding 상태 전이 불변식 — jqwik property test (이슈 #6 잡 2)

대상 로직: `GoodsFunding.increaseQuantityAndCloseIfNeeded`/`closeAsFailedIfDeadlinePassed`
(ADR-0002 A-1을 `GoodsFundingService`/`DeadlineSettlementScheduler`로 프로덕션 배선하는 과정,
이슈 #6 잡 2). 사람이 시나리오를 미리 떠올려 요청한 것이 아니라, CLAUDE.md가 요구하는 jqwik
stateful property test(참여 명령 + 정산 명령을 섞은 무작위 커맨드 시퀀스)를 작성해 실행하는 중
jqwik이 자동으로 축소(shrink)한 반례로 발견됐다.

| # | 시나리오 | 실제 재현 여부 | 원인 | 수정 내용 | 관련 커밋 |
|---|---|---|---|---|---|
| 1 | 정산 배치가 먼저 `FAILED`로 확정한 뒤, 그 시점 이후에도(예: 지연된 재시도, 또는 향후 새 호출 경로가 상태 체크 없이 호출하는 경우) `increaseQuantityAndCloseIfNeeded`가 호출되면 `remainingQuantity`만 보고 통과시켜 수량이 계속 증가하고, 목표에 도달하면 `FAILED -> FINISHED`로 재전이한다 | **재현됨** (jqwik 축소 결과: `commands=[0(정산), 10, 15, 15, 15, 15, 15, 15](참여)` — 정산으로 FAILED 확정 후 참여 누적으로 FINISHED 재전이) | `increaseQuantityAndCloseIfNeeded`가 `status`를 전혀 확인하지 않고 `targetQuantity - currentQuantity`만으로 수량 증가 가능 여부를 판단함. 현재 유일한 호출자인 `OrderService.participateGoodsFunding`은 호출 전 `status == RECRUITING`을 확인해서 실무에서는 막혀 있었지만, 그 가드는 호출자 쪽에만 있고 엔티티 자신은 "정산 후 상태 전이는 되돌아가지 않는다"는 불변식을 스스로 지키지 못했다 | `increaseQuantityAndCloseIfNeeded` 시작부에 `status != RECRUITING`이면 `GoodsException(RECRUITING_CLOSED)`를 던지는 가드 추가(`closeAsFailedIfDeadlinePassed`가 이미 대칭적으로 갖고 있던 가드와 동일 패턴). 회귀 테스트를 jqwik 반례를 축소한 그대로 `GoodsFundingTest`에 example-based로 고정 | 이번 커밋(GoodsFunding/GoodsFundingTest/GoodsFundingConcurrencyInvariantPropertyTest) |

### 회고 (#6, 잡 2 프로덕션 배선)

- 이 반례는 동시성(여러 스레드가 동시에 부르는 경합)이 아니라 **단일 스레드 안에서의 순서 위반**이다
  — jqwik property test가 "동시 요청 재현 테스트를 대체하지 않고 보완한다"고 CLAUDE.md에 명시된
  이유를 그대로 보여준다: `ExecutorService` 기반 통합 테스트(`DeadlineSettlementConcurrencyTest`)는
  "참여 vs 정산이 동시에 오는 경우"만 다루고, "정산 이후 시점에 참여가 뒤늦게 온다"는 순서는
  다루지 않았다.
- 현재 프로덕션 호출 경로(`OrderService`)에서는 이 버그가 실제로 발현되지 않는다 — 호출자 쪽
  가드가 이미 막고 있기 때문이다. 그럼에도 엔티티 레벨에서 고친 이유는, 이 메서드가 앞으로 새
  호출자(예: 관리자 도구, 배치 보정 스크립트)를 얻을 가능성이 있고, 그때마다 호출자가 매번
  `status`를 재확인해야 한다는 암묵적 전제에 의존하는 것보다 엔티티 스스로 불변식을 지키는 편이
  더 안전하기 때문이다.

## AI 검증자 자신의 오류 기록 (#6)

위 표는 "코드가 실패하는 시나리오"를 다루지만, 아래는 **AI(Claude)가 검증 과정에서 스스로 낸 오류**를
남긴다. AI를 적대적 검증자로 쓸 때 AI의 산출물 자체도 무비판적으로 신뢰해서는 안 된다는 근거로 남겨둔다.

| # | 오류 | 원인 | 발견 경위 | 교훈 |
|---|---|---|---|---|
| 1 | "OrderService가 `deadlineAt`을 전혀 검사하지 않는다"고 단정적으로 보고함(실제로는 line 35-36에 실시간 체크가 이미 존재) | `grep -n "increaseQuantityAndCloseIfNeeded\|deadlineAt\|findByIdForUpdate"`가 대소문자를 구분해 `getDeadlineAt()`의 `DeadlineAt`을 매칭하지 못함(소문자 `deadlineAt`만 찾음) — grep 결과가 "없음"을 "코드에 없음"으로 잘못 해석 | 사용자가 참여 vs 배치 경합에서 "마감을 넘긴 참여를 허용할 것인가"를 명시적으로 결정해달라고 요청 → 실제 코드 변경이 필요한지 확인하려고 `OrderService.java` 전체를 다시 Read했다가 발견 | grep 매치 실패(0건)는 "그 패턴이 코드에 없다"의 증거가 아니라 "그 정규식이 안 맞았다"는 증거일 뿐이다. 특히 `get`/`is` 접두어가 붙는 JPA 게터 필드명은 대소문자 변형이 흔하므로, 부정적 결론(`X가 없다`)을 내리기 전에는 반드시 해당 파일 전체를 직접 Read해서 확인해야 한다 |
| 2 | (검증 대상: 사용자가 지적) ADR-0002 초안의 "각 시나리오는 A x B 조합별로 반복 실행"이 시나리오 3(잡 1, 상태 미변경)까지 무차별 적용되어 A-1/A-2/A-3에 대해 동일 결과가 나올 실험을 3배 반복하게 설계되어 있었음 | ADR을 작성할 때 "일관된 실험 매트릭스"를 기계적으로 전 시나리오에 적용하고, 시나리오별로 어떤 축이 실제로 결과에 영향을 주는지 재검토하지 않음 | 사용자(사람)가 배경 설명("잡 1은 상태를 바꾸지 않는다")과 실험 설계 절의 모순을 직접 짚어냄 — AI가 스스로 발견한 게 아님 | AI가 작성한 실험/테스트 설계도 "일관성 있어 보인다"와 "논리적으로 필요하다"는 다르다. 각 축이 각 시나리오의 결과에 실제로 인과적으로 영향을 주는지 시나리오별로 재확인해야 한다 |
| 3 | (검증 대상: 사용자가 지적) ADR 초안에 "두 결과 중 정확히 하나만 성립"이라는 불변식만 있고, 마감을 넘긴 뒤 락을 먼저 잡은 참여가 성공해도 되는지(선착순 레이스 허용 여부)라는 실제 비즈니스 정책이 명시돼 있지 않았음 | 불변식(수학적 성질)과 정책 결정(비즈니스 요구사항)을 구분하지 않고, 불변식만 쓰면 설계가 끝났다고 판단함 | 사용자가 "이게 실제 의도된 정책인지 한 줄로 명시해야 한다"고 요청 | 동시성 실험 설계에서 "성립해야 하는 성질"을 적었다고 "왜 그 성질이면 충분한지"(비즈니스 의도)까지 적은 것은 아니다. 둘 다 별도로 명시해야 리뷰어가 의도와 우연을 구분할 수 있다 |

이 표의 #2, #3은 실제로는 실측 전(오늘 세션)에 사용자가 지적한 시점 기준이며, 지적 이후 해당 내용은
ADR-0002 "배경"·"실험 설계" 절에 반영해 수정했다. #1은 같은 세션에서 AI가 스스로 낸 오류이자 사용자
질문에 답하는 과정에서 사용자보다 먼저 발견하지 못하고 뒤늦게 자체 정정한 사례다.

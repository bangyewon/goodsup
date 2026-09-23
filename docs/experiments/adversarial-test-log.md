# 적대적 동시성 테스트 로그

AI(Claude)를 리뷰어가 아니라 "이 코드를 깨뜨리는 역할"로 활용한 기록입니다.
락 로직을 구현할 때마다, 구현이 끝난 직후 아래 프롬프트로 엣지케이스를 요청하고
실제로 재현·수정한 항목만 이 표에 남깁니다.

## 사용 프롬프트 템플릿

```
아래는 공구 참여(재고 차감) 동시성 제어 로직이야.
이 로직을 깨뜨릴 수 있는 타이밍 공격, 경합 조건(race condition), 엣지케이스를
가능한 한 많이 찾아줘. 각각에 대해:
1. 어떤 순서로 요청/이벤트가 발생해야 문제가 재현되는지
2. 실제로 어떤 데이터 정합성 문제가 발생하는지
3. 어떻게 재현 테스트 코드를 작성할 수 있는지
를 제시해줘.

[코드 붙여넣기]
```

## 발견 기록

대상 로직: `OrderService.participateGoodsFunding` (ADR-0001에 따라 `GoodsFundingRepository.findByIdForUpdate`
DB 비관적 락으로 보호되는 공구 참여/재고 차감 로직).

| # | 시나리오 (AI가 제안) | 실제 재현 여부 | 원인 | 수정 내용 | 관련 PR/커밋 |
|---|---|---|---|---|---|
| 1 | 같은 유저가 동일 공구에 quantity=maxQuantityPerUser로 두 번(동시 또는 순차) 참여하면, 매 요청이 "이번 요청 수량"만 `maxQuantityPerUser`와 비교해 합계가 1인당 한도를 초과할 수 있다 | **재현됨** | 기존 검증이 `goodsFunding.getMaxQuantityPerUser() < request.quantity()`로 이번 요청 수량만 확인하고, 해당 유저의 기존 누적 참여 수량을 조회하지 않음 | `OrdersRepository.sumQuantityByGoodsFundingIdAndUserId` 추가, 기존 참여량 + 이번 요청 수량의 합으로 검증하도록 변경. 이 조회도 GoodsFunding row 비관적 락으로 직렬화된 트랜잭션 내에서 수행되어 별도 락 없이 TOCTOU 방지 | 이번 커밋(OrderService/OrdersRepository/OrderServiceTest) |
| 2 | 락 획득 직후 애플리케이션 프로세스가 죽어 락이 해제되지 않는 경우 | 해당 없음 (구조적으로 발생 불가) | DB 비관적 락은 트랜잭션에 결부되므로 커넥션이 끊기면 DB가 트랜잭션을 롤백하며 락도 함께 해제됨. Redisson 같은 별도 TTL 관리가 필요 없음 — ADR-0001에서 B안을 선택한 이유이기도 함 | - | - |
| 3 | TTL 만료 직전에 두 요청이 겹쳐 이중으로 락을 획득하는 경우 | 해당 없음 | DB 비관적 락은 TTL 개념이 없고 트랜잭션 커밋/롤백 시점에만 해제되므로 이 공격 자체가 성립하지 않음 | - | - |
| 4 | 동시 참여 200건(목표 수량 50)을 몰아넣어 재고 오버셀링을 유도 | 재현 안 됨 | ADR-0001 부하 실험에서 실측: 3회 반복 모두 `currentQuantity`가 정확히 목표 수량과 일치, 오버셀링 없음 (`docs/adr/0001-concurrency-control-strategy.md` 참고) | - | - |
| 5 | 대규모 동시 요청으로 InnoDB 락 대기 타임아웃(기본 50초) 초과 → 예외 발생 | 재현 안 됨 (이번 규모에서는) | 동시 요청 200건 기준 에러율 0%, p99 지연도 150ms 내외로 타임아웃과는 거리가 멀었음. 다만 이는 단일 인스턴스·소규모 커넥션 풀 기준 실측이라 훨씬 큰 동시 접속 규모에서는 재검증이 필요 (ADR-0001 트레이드오프 절 참고) | - | - |

<!-- 재현되지 않은 시나리오도 "재현 안 됨 + 이유"로 남겨두면,
     어떤 경우까지 검토했는지 근거 자료가 됩니다. -->

## 회고

- AI가 제안한 5개 시나리오 중 1개(#1, 1인당 최대 수량 누적 미검증)가 실제 버그였다. 이는 동시성
  타이밍 문제가 아니라 순차 호출로도 재현되는 단순 검증 누락이었지만, "동시성 검증"을 요청하는
  과정에서 발견됐다는 점에서 적대적 검증 루틴이 순수 동시성 버그 외의 로직 결함도 걸러낼 수 있음을 보여준다.
- #2, #3(Redisson 특유의 TTL/프로세스 크래시 시나리오)은 ADR-0001에서 DB 비관적 락을 채택한 이유와
  정확히 대칭되는 항목이었다 — 즉 이번 전략 선택 자체가 해당 공격 표면을 원천 제거했다.
  전략을 바꾸면(예: 추후 다른 로직에 Redisson을 다시 쓰게 되면) 이 두 시나리오는 재평가가 필요하다.
- #5는 사람이 먼저 떠올리기 쉽지 않은 "락 대기 타임아웃" 종류의 반례였는데, 현재 규모에서는 재현되지
  않았지만 향후 트래픽이 커지면 재실험이 필요하다는 한계를 기록해 둔 것 자체가 의미가 있었다.

---

대상 로직: 마감 정산 배치 후보 A-1(`PessimisticLockSettlementBatch`)/A-2(`BulkUpdateSettlementService`),
B-1(분산락 없음, 알림 unique 제약)의 상태 전이·중복 실행 방지 (ADR-0002, 이슈 #6).

| # | 시나리오 | 실제 재현 여부 | 원인 | 수정 내용 | 관련 커밋 |
|---|---|---|---|---|---|
| 1 | 참여 vs 배치가 마지막 한 자리를 두고 동시 경합하면 `FINISHED`/`FAILED`가 동시에 확정되거나 재고 불변식이 깨질 수 있다 | 재현 안 됨 (A-1/A-2 둘 다, 3회 반복 총 12회 실행) | A-1은 ADR-0001과 동일한 `findByIdForUpdate` 비관적 락으로, A-2는 조건부 UPDATE의 원자성으로 각각 직렬화됨 | - | - |
| 2 | 마감 정산 배치가 중첩·중복 실행되면 상태 전이나 `FUNDING_FAILED` 알림이 중복 발생할 수 있다 | 재현 안 됨 (A-1/A-2 둘 다, B-1만으로) | 조건부 UPDATE(`WHERE status='RECRUITING'`)가 자연히 멱등이고, `Notification` unique 제약(`uk_notification_user_funding_type`) + insert 전 존재 확인이 이중 방어선 역할을 함 | - | - |
| 3 | (테스트 하네스 자체의 결함) 여러 정산 후보를 같은 부모 `AbstractConcurrencyIntegrationTest`를 상속해 실측 비교하면, Testcontainers `@Container` static 필드가 먼저 끝난 서브클래스에서 stop되어 나중 서브클래스가 죽은 컨테이너에 연결 시도 | **재현됨** | `@Container`(Testcontainers JUnit5 확장)로 선언한 static 필드는 부모 클래스가 소유해 여러 서브클래스가 공유하는데, 확장이 "먼저 시작한 서브클래스"의 afterAll에서 stop시킴 | 싱글턴 컨테이너 패턴(`static { mysql.start(); }`, `@Container` 제거)으로 전환 | 이번 커밋(AbstractConcurrencyIntegrationTest) |
| 4 | (테스트 하네스 자체의 결함) 여러 후보 테스트 클래스가 같은 DB를 공유하는데 고정된 이메일(`host1`, `host2` 등)을 재사용하면 클래스 간 유니크 제약 충돌이 날 수 있다 | **재현됨** | 정리(cleanup) 없이 동일 이메일을 여러 테스트 클래스가 순서대로 insert | `AbstractSettlementConcurrencyExperimentTest`에 `@AfterEach`로 FK 순서(orders→notification→goods_funding→user) 정리 추가 | 이번 커밋 |
| 5 | (테스트 하네스 자체의 결함) 사전 참여 단계의 마감 버퍼가 너무 촉박하면 환경이 느릴 때 정상 참여도 거절될 수 있다 | **재현됨** | 시나리오 2의 `deadlineAt = now + 1초` 버퍼로 순차 참여 19회를 끝내기엔 부족 | 버퍼를 10초로 확대 | 이번 커밋 |

## 회고 (#6)

- 순수 동시성 제어 로직(A-1/A-2/B-1)에서는 반례가 하나도 재현되지 않았다 — ADR-0001에서 검증된
  "명시적 락으로 보호"라는 원칙을 그대로 재사용한 결과로 해석한다.
- 대신 검증 과정에서 테스트 하네스 자체의 결함 3건(#3~#5)을 찾았다. 이들은 실제 프로덕션 동시성
  버그가 아니라 "여러 후보를 나란히 실측 비교"하는 이번 ADR 특유의 작업 방식에서 처음 드러난
  문제였다(#5 이전까지는 정산 후보가 하나뿐이라 컨테이너 공유·데이터 정리 문제가 없었음). 실측
  비교 자체가 새로운 종류의 반례(테스트 인프라 버그)를 드러낼 수 있다는 점을 보여준다.
- A-2(벌크 UPDATE)가 정합성은 통과했지만 지연이 A-1 대비 8~9배 느리게 측정된 것은 사전 가설과
  반대되는 결과였다(ADR-0002 실측 결과 절 참고). "직관적으로 빠를 것 같은 단일 SQL"이 실측 없이는
  근거가 될 수 없다는 CLAUDE.md 원칙을 다시 한번 확인시켜준 사례다.

---

대상 로직: `NotificationService.notifyDeadlineSoon`/`DeadlineSoonNotificationScheduler`(이슈 #6 잡 1, 마감임박 알림).

| # | 시나리오 | 실제 재현 여부 | 원인 | 수정 내용 | 관련 커밋 |
|---|---|---|---|---|---|
| 1 | 스케줄러가 임박 대상 id를 조회한 시점엔 `RECRUITING`이었지만, 그 이후 `NotificationService.notifyDeadlineSoon`이 실제로 실행되는 시점 사이에(참여로 목표 달성 → `FINISHED`, 또는 배치 정산 → `FAILED`) 상태가 바뀌면, 이미 끝난 공구에도 여전히 "마감임박" 알림이 나간다 | **재현됨** | `notifyDeadlineSoon`이 `GoodsFunding`을 FK 참조용 프록시(`getReferenceById`, 쿼리 없음)로만 사용하고 현재 상태를 재확인하지 않음 — 후보 조회와 발송 사이의 시간차를 고려하지 않은 설계 누락 | `GoodsFundingService.getReference`를 제거하고 `findRecruiting(id)`(실제 조회 + `status == RECRUITING` 필터)로 교체, `notifyDeadlineSoon`은 이 조회 결과가 없으면(이미 RECRUITING이 아니면) 즉시 0건 반환하고 종료 | 이번 커밋(GoodsFundingService/NotificationService/NotificationServiceTest) |
| 2 | 배치가 중첩·중복 실행되면 `DEADLINE_SOON` 알림이 참여자 수보다 많이 발송될 수 있다 | 재현 안 됨 (통합 테스트로 실측, ADR-0002가 미뤄둔 시나리오 3) | `existsBy` 사전체크 + `Notification` 유니크 제약(`uk_notification_user_funding_type`) + 개별 `DataIntegrityViolationException` catch 조합(B-1 패턴)이 잡 1에도 동일하게 유효함을 실측 확인 — 참여자 19명, 동시 호출 10회에서도 알림은 정확히 19건 | - | - |
| 3 | `threshold-hours` 설정값이 0 이하이거나 매우 크면 어떻게 되는가 | 해당 없음 (데이터 정합성 문제 아님) | `now.plusHours(threshold)`가 `now`보다 이전이 되면 BETWEEN 조건이 항상 거짓이 되어 후보가 조회되지 않을 뿐, 예외나 오동작은 없음 — 운영 설정값의 문제이지 로직 결함이 아님 | - | - |
| 4 | 스케줄러 조회 이후 대상 공구가 삭제되면 어떻게 되는가 | 해당 없음 | 현재 코드베이스에 `GoodsFunding` 삭제 기능 자체가 없음(`grep` 확인) — 발생 불가능한 시나리오 | - | - |

## 회고 (#6, 잡 1)

- 시나리오 1은 동시성 타이밍 문제가 아니라 "두 개의 분리된 트랜잭션(후보 조회 vs 실제 발송) 사이의 시간차"에서 나온 일반적인 stale-read 문제였다 — ADR-0002가 이미 검증한 "동시 경합"과는 다른 종류의 반례였다는 점에서, 같은 배치라도 "동시에 여러 번 도는 경우"와 "한 번 도는 동안 시간이 흐르는 경우"를 별도로 검토해야 한다는 교훈을 남긴다.
- 시나리오 2(중복 실행 방지)는 ADR-0002가 "잡 1이 아직 구현되지 않아 실행하지 못했다"고 명시적으로 남겨뒀던 후속 작업이었다 — 이번 구현과 함께 실제로 실행해 B-1 패턴이 잡 1에도 유효함을 실측으로 닫았다.

---

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

---

대상 로직: `PaymentFanOutRelayScheduler`/`OutboxEventService`/`PaymentService`의 결제 fan-out
claim/lease/재시도(ADR-0004 A안: Outbox + 폴링 릴레이, 이슈 #9). CLAUDE.md 절차대로 Claude가 먼저
후보 시나리오(A1~A5)를 제안하고, 사람이 전부 선정해 `PaymentFanOutRelayConcurrencyTest`로 재현했다.

| # | 시나리오 | 실제 재현 여부 | 원인 | 수정 내용 | 관련 커밋 |
|---|---|---|---|---|---|
| A1 | 참여 레이스로 마지막 한자리를 두고 경합해도 `PAYMENT_FANOUT_REQUESTED` outbox row가 정확히 1건만 생성되는가 | 재현 안 됨(정상 동작 확인) | `OrderService.participateGoodsFunding`이 `GoodsFunding` 비관적 락 트랜잭션 안에서 Payment 생성·outbox insert까지 원자적으로 묶어, 목표 달성 전이가 단 한 번만 일어남(ADR-0001과 동일한 보호) | - | - |
| A2 | lease(`LEASE_TIMEOUT`=5분) 초과로 다른 워커가 같은 outbox row를 재claim하면, 원래 워커와 새 워커가 동시에 같은 결제를 PG에 중복 호출하고, 뒤늦게 완료 처리하려는 워커가 예외를 던질 수 있다 | **재현됨** | PG 호출은 트랜잭션 밖에서 수행되므로(금지 패턴 회피) claim 이후엔 lease만으로 소유권을 주장한다 — 두 워커가 동시에 `chargeOne`을 호출하면 PG 호출 자체가 중복되고(실측: `order1 PG 호출 횟수=2`), 뒤늦은 워커가 이미 `PROCESSED`인 row에 `OutboxEvent.markProcessed()`를 호출하며 `IllegalStateException`이 `runOnce`의 `DataAccessException` catch를 뚫고 전파됨(실측 로그: `worker1Failure=java.lang.IllegalStateException: PROCESSING 상태에서만 완료 처리할 수 있습니다`) | `OutboxEvent.markProcessed()`가 이미 `PROCESSED`면 예외 대신 조용히 흡수하도록 변경(`Payment.markSucceeded/markFailed`와 동일한 멱등 패턴). PG 이중 호출 자체(외부 호출 레벨의 at-least-once)는 idempotency key(`"order-" + orderId`)에 의존하는 기존 설계를 그대로 유지 — DB 상태(Payment)는 두 경우 모두 정합했다 | 이번 커밋(OutboxEvent/PaymentFanOutRelayConcurrencyTest) |
| A3 | 이미 `SUCCEEDED`인 결제가 다음 재시도 사이클에서 다시 PG 호출되지 않는가 | 재현 안 됨(정상 동작 확인) | `PaymentService.findRequestedPaymentIdsByGoodsFundingId`가 `status=REQUESTED`만 조회해 이후 사이클의 후보 목록에서 자연히 제외됨(실측: 성공건 PG 호출 1회 고정) | - | - |
| A4 | `MAX_ATTEMPTS`(5) 소진 후 `markAllRemainingRequestedAsFailed`가 이미 `SUCCEEDED`된 결제는 보존하고 `REQUESTED`만 `FAILED`로 확정하는가 | 재현 안 됨(정상 동작 확인) | 실패건만 5회 재시도 후 `FAILED` 확정, 성공건은 그대로 `SUCCEEDED` 유지(실측 로그: `성공건 PG 호출=1회, 실패건 PG 호출=5회`) | - | - |
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

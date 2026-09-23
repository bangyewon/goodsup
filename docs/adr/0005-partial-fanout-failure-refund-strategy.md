# ADR 0005: 결제 fan-out 부분 실패 시 취소·환불 처리 전략

- 상태: 논의 중 (구현 전 초안 — 사람 확인 필요, 아직 코드 작성 안 함)
- 작성일: 2026-09-23
- 관련 이슈: #9 (개정된 작업 범위 항목 "개별 Payment/Orders 단위 자동 환불 트리거"의 케이스 (1))
- 관련 ADR: [0001](./0001-concurrency-control-strategy.md), [0003](./0003-payment-timing-strategy.md), [0004](./0004-payment-fanout-trigger-strategy.md)

## 배경

ADR-0003이 B안(목표 달성 후 결제)을 채택하면서 남겨둔 미해결 질문: "목표 달성 후 결제 실패 시…
결제 실패한 참여자를 어떻게 처리할지 별도 정책 필요"가 이 ADR의 대상이다.

현재 `PaymentFanOutRelayScheduler`(ADR-0004 A안, 구현·실측 완료)는 `MAX_ATTEMPTS` 소진 시
`PaymentService.markAllRemainingRequestedAsFailed`로 **아직 `REQUESTED`인 결제만** `FAILED`로
확정하고 끝난다(`PaymentFanOutRelayScheduler.java` 74-79행). 이 시점에 같은 `GoodsFunding`에
속한 **이미 `SUCCEEDED`된 결제들**에는 아무 일도 일어나지 않는다:

- `Payment`는 `SUCCEEDED`로 남아있고(PG 승인은 이미 끝난 상태),
- `Orders.deliveryStatus`는 여전히 `WAITING`이며, 애초에 `DeliveryStatus` enum에 취소를 표현할
  값 자체가 없다(`WAITING/PREPARING/SHIPPING/DELIVERED`뿐),
- 참여자에게 아무 통지도 나가지 않는다.

즉 "목표는 달성했지만 일부 참여자의 결제가 끝내 실패한" 그룹을 어떻게 마무리할지가 완전히
비어있다. 참고로 `PaymentStatus`(`CANCELLED`, `REFUNDED`)와 `NotificationType`
(`REFUND_COMPLETED`)에는 이미 이 흐름을 위한 값이 준비돼 있지만(스키마상 선제적으로 만들어둔
것으로 보임) 어디서도 사용되지 않는다.

이 문제를 이전 대화에서 "outbox 없이 vs outbox로" 두 흐름으로 비교했고, 사용자가 **outbox
기반으로 가는 쪽을 선택**했다. 이 ADR은 그 선택을 구체적인 설계로 확정하는 문서다.

### 이슈 #9 범위 조정

이슈 #9 개정판은 "개별 Payment/Orders 단위 자동 환불 트리거" 항목에 3개 케이스를 명시한다:
(1) fan-out 부분 실패, (2) PG 사후 취소/차지백 웹훅, (3) 배송 불가 등 운영 사유. 이 ADR은
**(1)만** 다룬다. (2)는 웹훅 수신 엔드포인트(이슈 #9 미착수 항목)가, (3)은 운영자용 취소
API(아직 설계되지 않음)가 선행돼야 해서, 존재하지 않는 인프라를 가정한 설계를 지금 확정하는 건
시기상조라고 판단해 별도 ADR로 미룬다.

## 비교 대상

### A. Outbox 없이 — 스케줄러 안에서 직접 처리

`processOne`의 `terminallyFailed` 분기(같은 트랜잭션에 Orders 취소 상태 전환을 포함) 직후,
같은 스케줄러 메서드 안에서 환불 API·알림을 순차 호출. `DeadlineSettlementScheduler`가 이미
쓰는 "커밋 후 직접 호출" 패턴과 동일.

- 장점: 코드가 단순하고 별도 이벤트 타입/릴레이가 필요 없다.
- 단점: 환불 API 호출이 프로세스 크래시나 예외로 스킵되면 그 outbox row는 이미
  `markPendingForRetry`/`FAILED`로 소진된 뒤라 **재시도할 방법이 없다**. 결제 실패 통지·환불은
  금전이 걸린 사안이라 "최선 노력, 실패해도 무방"으로 다루기 어렵다.

### B. Outbox로 — 새 이벤트 타입 + 전용 릴레이 (선택된 방향)

`terminallyFailed` 분기(Payment를 `FAILED`로 마킹하는 트랜잭션)와 **같은 트랜잭션**에 새
`OutboxEvent`(`PAYMENT_CANCELLATION_REQUESTED`, `aggregateId = goodsFundingId`)를 원자적으로
insert. 별도 릴레이가 이 row를 claim해 트랜잭션 밖에서 환불 API·알림을 수행하고, 기존
claim/lease/재시도 메커니즘(ADR-0004에서 이미 실측 완료)을 그대로 재사용한다.

- 장점: fan-out 자체와 동일한 신뢰성 보장(원자적 기록 + claim/lease/재시도)을 재사용만으로 얻는다.
- 단점: `OutboxEventType`이 2종류가 되고, `(event_type, aggregate_id)` 유니크 제약이 실제로
  의미를 갖게 된다(같은 `goodsFundingId`에 대해 `PAYMENT_FANOUT_REQUESTED`와
  `PAYMENT_CANCELLATION_REQUESTED`가 공존해야 함).

## 결정 (초안)

**B안을 채택한다.** 근거는 위 비교에서 이미 정리됐고, 사용자가 이전 대화에서 직접 선택했다.

### 상세 설계

- **트리거 지점**: `PaymentFanOutRelayScheduler.processOne`의 `terminallyFailed` 분기.
  `paymentService.markAllRemainingRequestedAsFailed(goodsFundingId)`와 같은 트랜잭션에서
  `outboxEventService.recordPaymentCancellationRequested(goodsFundingId, now)` 호출 추가.
  (현재 이 분기는 트랜잭션 경계가 없는 일반 메서드이므로, 두 호출을 하나의 `@Transactional`
  서비스 메서드로 묶는 리팩터링이 필요 — 트랜잭션 경계 자체가 바뀌는 지점이라 이 ADR의
  핵심 결정 사항 중 하나.)
- **새 이벤트 타입**: `OutboxEventType.PAYMENT_CANCELLATION_REQUESTED`.
- **새 상태 값 사용**:
  - `DeliveryStatus.CANCELLED` 추가 필요(현재 값 없음).
  - `Orders`에 상태 전환 메서드 추가 필요(`Payment`처럼 불변식 가드 포함).
  - `Payment.SUCCEEDED` → 환불 API 성공 시 `Payment.REFUNDED`로 전환하는 메서드 추가
    (`markRefunded()`, `markSucceeded/markFailed`와 동일한 멱등 패턴 — 이미 `REFUNDED`면 무시).
  - `NotificationType.REFUND_COMPLETED` 발송 로직 추가(`NotificationService`에 대응 메서드 없음 —
    `notifyFundingFailed`와 동일한 패턴으로 추가).
- **PG 게이트웨이 확장**: `PgPaymentGateway`에 `refund(PgRefundRequest)` 추가 필요(현재
  `charge`만 존재). `LoggingPgPaymentGateway`도 플레이스홀더로 확장.
- **릴레이**: 기존 `PaymentFanOutRelayScheduler`에 이벤트 타입별 분기를 추가할지, 별도
  `PaymentCancellationRelayScheduler`를 새로 만들지는 미결정 — 구현 단계에서 코드 중복도를 보고
  판단(claim/lease/재시도 로직 자체는 `OutboxEventService`에 이미 있어 재사용 가능, 타입별로
  다른 건 "claim 이후 실제로 무엇을 하는가"뿐이라 별도 스케줄러 쪽이 책임 분리 측면에서 더
  나아 보이지만 최종 판단은 보류).
- **처리 대상 조회**: 새 `PaymentRepository` 쿼리 필요 — 해당 `goodsFundingId`의 `SUCCEEDED`
  결제 전부(`findIdsByOrdersGoodsFundingIdAndStatus`를 `SUCCEEDED`로 재사용 가능, 신규 쿼리
  불필요할 수 있음).

## 실험 설계 (구현 후 실측 필요 — CLAUDE.md "AI를 검증 파트너로 활용" 절차)

ADR-0004의 A2(lease 재claim 시 PG 중복 호출)와 동일한 구조적 리스크가 환불 경로에도 그대로
적용된다 — 환불 API도 claim/lease 기반이므로 두 워커가 같은 결제를 동시에 환불 요청할 수 있다.
구현 후 다음을 `PaymentFanOutRelayConcurrencyTest`와 유사한 통합 테스트로 재현·실측한다:

1. 같은 환불 대상 결제에 대해 두 워커가 동시에 환불 API를 호출할 수 있는가(ADR-0004 A2와 동일
   패턴 재현 — idempotency key를 환불 요청에도 적용해야 하는지 확인).
2. 이미 `REFUNDED`된 결제가 재시도 사이클에서 다시 호출되지 않는가(ADR-0004 A3 패턴 재사용).
3. `Orders.CANCELLED` 전환과 `Payment.REFUNDED` 전환 사이에 불변식 위반(예: 환불 실패했는데
   Orders만 취소됨)이 생길 수 있는가.

재현 여부와 무관하게 모든 시나리오를 `docs/experiments/adversarial-test-log.md`에 기록한다.

## 트레이드오프

*(구현 후 채움 — 실측 결과에 따라 위 "상세 설계"의 미결정 사항 확정 후 정리.)*

## 참고 — 향후 실측이 필요한 하위 질문

- 릴레이를 기존 스케줄러에 합칠지 분리할지(위 "상세 설계" 참고).
- 이슈 #9 케이스 (2)(PG 웹훅)·(3)(운영자 취소)은 이 ADR이 만드는 `Orders.CANCELLED`/
  `Payment.REFUNDED` 상태 전환과 알림 로직을 재사용할 수 있을 가능성이 높다 — 해당 인프라가
  갖춰지면 이 ADR을 참고해 후속 ADR로 확장한다.

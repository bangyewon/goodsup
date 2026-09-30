<p align="center">
  <img src="docs/assets/logo.svg" alt="GoodsUp" width="300">
</p>

팬덤 굿즈 공동구매 백엔드. 목표 수량을 채운 공구만 결제하고, 마감 직전에 요청이 몰려도 참여 수량이 목표를 넘지 않게 하는 것이 이 프로젝트의 핵심 과제다.

Java 21 · Spring Boot 3 · MySQL · JUnit5 · Testcontainers · jqwik

## 현재 상태

구현된 것: 공구 개설과 참여, 마감 정산 배치, 마감 임박 알림, 결제 fan-out(outbox), PG 웹훅 수신.

아직 없거나 확인하지 못한 것:

- PG는 `LoggingPgPaymentGateway` 플레이스홀더다. 실제 PG의 응답 지연과 타임아웃은 측정하지 못했다.
- 가상계좌 입금 기한, 실패 사유별 재시도 구분은 구현 전이다. ([ADR-0006](docs/adr/0006-pending-payment-state-and-timeout-policy.md))
- 배송 상태(`DeliveryStatus`)는 초기값만 있고 전이 코드는 없다.
- 아래 수치는 로컬 Docker, 단일 인스턴스 기준이다. 운영 규모의 근거로 쓰기는 어렵다.

## 동시성 전략 비교

<p align="center">
  <img src="docs/assets/benchmark.svg" alt="동시성 전략 실측 비교" width="760">
</p>

재고 차감은 Redisson 락, DB 비관적 락, 낙관적 락 세 가지를 같은 조건에서 돌렸다. 셋 다 초과 판매는 없었고, 처리량과 지연이 가장 좋은 DB 비관적 락으로 정했다. ([ADR-0001](docs/adr/0001-concurrency-control-strategy.md))

마감 정산은 벌크 UPDATE가 더 빠를 거라 예상했는데 테스트 실행 시간이 8~9배 길었다. 원인은 확정하지 못했고(조건에 맞는 인덱스가 없어 참여 트랜잭션과 락을 다투는 것으로 의심), 기존 락 방식을 그대로 쓰기로 했다. ([ADR-0002](docs/adr/0002-deadline-settlement-batch-concurrency.md))

## 테스트하다 찾은 버그

락 로직을 짤 때마다 Claude에게 깨뜨릴 시나리오를 뽑게 하고, 실제로 재현되는 것만 골라 테스트로 만들었다. 재현되지 않은 시나리오도 이유와 함께 [로그](docs/experiments/adversarial-test-log.md)에 남겼다. 재현된 것은 다음과 같다.

- **1인당 구매 한도 누적 미검증**: 검증이 이번 요청 수량만 한도와 비교해서, 같은 사용자가 한도만큼 두 번 참여하면 통과했다. 동시성이 아니라 순차 호출로도 재현되는 단순 누락이었다. 기존 참여 수량을 합산하도록 고쳤다.
- **끝난 공구에 마감 임박 알림 발송**: 스케줄러가 대상을 조회한 뒤 알림을 보내기 전에 공구가 `FINISHED`나 `FAILED`가 되면 그대로 알림이 나갔다. 발송 시점에 상태를 다시 조회하도록 바꿨다.
- **워커 lease 만료 후 결제 중복 호출**: 두 워커가 같은 outbox row를 잡으면 같은 주문을 PG에 두 번 호출했다. 늦게 끝난 워커는 이미 처리된 row에 완료 처리를 하다 예외를 던졌다. 완료 처리를 멱등하게 만들었다. PG 중복 호출 자체는 idempotency key에 맡겼는데, 실제 PG 연동 전이라 검증은 안 됐다.

테스트 코드 쪽 문제도 있었다. 지연을 재현하려고 짠 테스트가 스스로 데드락에 걸렸고, 클래스별로는 통과하던 테스트가 전체 스위트에서만 실패했다(다른 테스트가 남긴 데이터 때문이었다).

## 서비스 흐름

1. 공구를 만든다. 목표 수량과 마감 시각이 있고 상태는 `RECRUITING`이다.
2. 사용자가 수량과 결제수단(카드, 가상계좌)을 골라 참여한다. 이때는 수량만 확보하고 결제하지 않는다.
3. 마지막 한 자리가 차는 참여 트랜잭션에서 공구가 `FINISHED`가 된다.
4. 마감까지 못 채운 공구는 정산 배치가 `FAILED`로 바꾼다. 마감 임박 알림도 배치에서 보낸다.
5. `FINISHED`가 되면 참여자 전원에게 결제를 요청한다. 참여 트랜잭션 안에서는 PG를 호출하지 않는다. ([ADR-0003](docs/adr/0003-payment-timing-strategy.md))

결제 요청은 outbox 테이블에 남겨 두고 스케줄러가 읽어 처리한다. Debezium CDC도 컨테이너로 띄워 봤는데 커밋부터 Kafka 토픽 도착까지 p50 496ms였다. 결제 시작에 그 속도는 필요 없고 Kafka 운영 부담이 더 크다고 판단해 폴링으로 정했다. ([ADR-0004](docs/adr/0004-payment-fanout-trigger-strategy.md))

| API | 설명 |
|---|---|
| `POST /api/goods-fundings` | 공구 개설 |
| `POST /api/goods-fundings/{id}/orders` | 공구 참여 (재고 차감) |
| `GET /api/orders/{orderId}/payment` | 결제 상태 조회 |
| `POST /api/payments/webhook` | PG 결제 결과 수신 (HMAC-SHA256 서명 검증) |

나머지 API는 실행 후 Swagger UI(springdoc)에서 볼 수 있다.

## 설계 결정 (ADR)

| # | 주제 | 상태 |
|---|---|---|
| [0001](docs/adr/0001-concurrency-control-strategy.md) | 공구 참여 동시성 제어 | 채택 |
| [0002](docs/adr/0002-deadline-settlement-batch-concurrency.md) | 마감 정산 배치 동시성 | 채택 |
| [0003](docs/adr/0003-payment-timing-strategy.md) | 결제 시점 (목표 달성 후 결제) | 채택 |
| [0004](docs/adr/0004-payment-fanout-trigger-strategy.md) | 결제 fan-out 트리거 | 채택 |
| [0005](docs/adr/0005-partial-fanout-failure-refund-strategy.md) | 부분 실패 환불 | 초안 |
| [0006](docs/adr/0006-pending-payment-state-and-timeout-policy.md) | PENDING 상태·타임아웃 | 부분 구현 |

## 구조와 실행

```
back/src/main/java/com/goodsup/demo
├── goods         공구
├── orders        참여·주문
├── payment       결제 fan-out, PG 웹훅
├── notification  마감 임박 알림
├── user
└── common        ApiResponse, GlobalExceptionHandler
```

Controller → Service → Repository 3계층이고, 트랜잭션은 Service에서만 건다. 전체 규칙은 [CLAUDE.md](CLAUDE.md)에 있다.

```bash
cd back
./gradlew test    # Docker 필요 (Testcontainers)
```

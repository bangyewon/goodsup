<p align="center">
  <img src="docs/assets/logo.svg" alt="GoodsUp" width="300">
</p>

<p align="center"><em>마감 시각에 몰리는 결제 요청에도, 목표 수량을 절대 넘기지 않는다.</em></p>

<p align="center">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-orange">
  <img alt="Spring Boot 3" src="https://img.shields.io/badge/Spring_Boot-3.x-6DB33F">
  <img alt="MySQL" src="https://img.shields.io/badge/MySQL-8.0-4479A1">
  <img alt="jqwik" src="https://img.shields.io/badge/tests-JUnit5_%2B_jqwik-blue">
</p>

<p align="center"><strong>0% 초과 판매 · 851 TPS (DB 비관적 락) · p99 148ms</strong></p>

<p align="center">
  <img src="docs/assets/benchmark.svg" alt="동시성 전략 비교 그래프" width="760">
</p>

<p align="center"><sub>ADR-0001 실측 값 (ExecutorService 동시 호출 통합 테스트, MySQL 8.0). 조건과 원본 로그는 <a href="docs/adr/0001-concurrency-control-strategy.md">ADR-0001</a> 참고.</sub></p>

* * *

팬덤 굿즈 **공동구매 · 마감 임박 결제 시스템**. 목표 수량을 달성한 공구만 결제가 확정됩니다.
핵심 과제는 마감 직전에 몰리는 **동시 참여 요청의 정합성 제어**이고, 이 저장소는 그 결정을
"감"이 아니라 **실측과 반례 테스트**로 내린 기록이기도 합니다.

## 서비스 개요

1. **공구 개설** — 굿즈, 목표 수량, 마감 시각을 정해 공구(`GoodsFunding`)를 엽니다. 상태는 `RECRUITING`.
2. **참여** — 사용자가 수량을 담아 주문합니다. 이 시점에는 결제하지 않고 수량만 확보합니다.
3. **마감 정산** — 마감 시각에 배치가 목표 수량 달성 여부를 판정합니다. 달성하면 `FINISHED`, 미달이면 `FAILED`.
   마감 임박 시점에는 참여자에게 알림을 보냅니다.
4. **결제** — `FINISHED`된 공구의 참여자 전원에게 결제를 fan-out 합니다. `FAILED`면 결제가 일어나지 않습니다.
5. **배송** — 결제가 끝난 주문은 `WAITING → PREPARING → SHIPPING → DELIVERED`로 진행합니다.

| API | 설명 |
|---|---|
| `POST /api/users/register` | 회원가입 |
| `POST /api/goods-fundings` | 공구 개설 |
| `GET /api/goods-fundings`, `GET /api/goods-fundings/{id}` | 공구 목록·상세 |
| `POST /api/goods-fundings/{id}/orders` | 공구 참여 |
| `GET /api/orders/{orderId}/payment` | 결제 상태 조회 |
| `POST /api/payments/webhook` | PG 결제 결과 수신 (HMAC-SHA256 서명 검증) |

## 실측으로 고른 동시성 전략

세 가지 구현을 같은 조건에서 비교했습니다 ([ADR-0001](docs/adr/0001-concurrency-control-strategy.md)).

| | A. Redisson RLock | **B. DB 비관적 락** | C. 낙관적 락 + 재시도 |
|---|---|---|---|
| 재고 오차율 | 0% | **0%** | 0% |
| TPS | 334.45 | **851.11** | 256.37 |
| p95 | 458.33ms | **132.33ms** | 772.67ms |
| p99 | 564.67ms | **148.33ms** | 798.33ms |

정합성은 셋 다 같았고 처리량·지연·에러율에서 B가 앞서 **B를 채택**했습니다. 별도 인프라(Redis)도 필요 없습니다.

## 어떻게 검증하나

1. **동시 요청 재현** — `ExecutorService` 기반 통합 테스트 (Testcontainers MySQL)
2. **불변식 검증** — jqwik stateful property test ("참여 수량 ≤ 목표 수량", "정산 후 상태는 되돌아가지 않는다")
3. **적대적 리뷰** — Claude에게 코드를 "깨뜨리는 역할"을 맡기고, 재현된 반례만 회귀 테스트로 고정
   → [적대적 테스트 로그](docs/experiments/adversarial-test-log.md)

> Claude의 분석 자체는 근거가 아닙니다. 실제 테스트 결과만 결정의 근거가 됩니다.

## 결제 흐름

```
참여(재고 차감, 비관적 락) → 마감 정산 배치 → 목표 달성 시 결제 fan-out
```

- 목표 달성 후 결제하므로 미달 공구에는 환불 자체가 없습니다 ([ADR-0003](docs/adr/0003-payment-timing-strategy.md))
- 참여 트랜잭션에는 PG 호출이 없습니다 (트랜잭션 안 외부 API 동기 호출 금지)

## 설계 결정 (ADR)

| # | 주제 | 상태 |
|---|---|---|
| [0001](docs/adr/0001-concurrency-control-strategy.md) | 공구 참여 동시성 제어 | 채택 |
| [0002](docs/adr/0002-deadline-settlement-batch-concurrency.md) | 마감 정산 배치 동시성 | 채택 |
| [0003](docs/adr/0003-payment-timing-strategy.md) | 결제 시점 (목표 달성 후 결제) | 채택 |
| [0004](docs/adr/0004-payment-fanout-trigger-strategy.md) | 결제 fan-out 트리거 | 보류 |
| [0005](docs/adr/0005-partial-fanout-failure-refund-strategy.md) | 부분 실패 환불 | 초안 |
| [0006](docs/adr/0006-pending-payment-state-and-timeout-policy.md) | PENDING 상태·타임아웃 | 초안 |

## 구조

```
back/src/main/java/com/goodsup/demo
├── goods         공구 도메인
├── orders        참여·주문
├── payment       결제 fan-out, PG 웹훅 (HMAC-SHA256 서명 검증)
├── notification  마감 임박 알림
├── user
└── common        ApiResponse, GlobalExceptionHandler
```

Controller → Service → Repository 3계층. 트랜잭션은 Service에서만, 재고 로직은 반드시 락으로 보호.
전체 규칙은 [CLAUDE.md](CLAUDE.md).

## 실행

```bash
cd back
./gradlew test    # Docker 필요 (Testcontainers)
```

## 기술 스택

Spring Boot 3.x · Java 21 · MySQL · Spring Data JPA · Spring Security · springdoc · JUnit5 · Testcontainers · jqwik

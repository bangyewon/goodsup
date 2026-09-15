package com.goodsup.demo.common;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
public abstract class AbstractConcurrencyIntegrationTest {

    /**
     * static 필드는 이 부모 클래스가 소유하므로 서로 다른 서브클래스 여러 개가 상속하면
     * 같은 컨테이너 인스턴스를 공유한다. {@code @Container}(Testcontainers JUnit5 확장)로
     * 선언하면 먼저 끝난 서브클래스의 afterAll에서 컨테이너가 stop되어, 나중에 실행되는
     * 서브클래스가 죽은 컨테이너에 연결을 시도하는 문제가 있다(ADR-0002 실험 중 재현: A-1/A-2
     * 두 서브클래스를 같은 실행에서 돌리자 두 번째 클래스의 테스트가 전부 커넥션 실패로 죽음).
     * 싱글턴 컨테이너 패턴으로 직접 시작하고 JVM 종료(Ryuk) 시에만 정리되도록 한다.
     */
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    static {
        mysql.start();
    }
}

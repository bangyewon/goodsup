package com.goodsup.demo.payment.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * ADR-0004 B안(Outbox + CDC 릴레이) 경량 PoC. 프로덕션 컨슈머는 만들지 않고 두 가지만 본다.
 * (1) outbox row insert가 Debezium을 거쳐 Kafka 토픽에 실제로 나타나는가
 * (2) 커밋~토픽 도달 지연(p50/p95)
 *
 * 컨테이너 3개(MySQL binlog ROW, Kafka, Debezium Connect)를 띄우므로 기본 빌드에서는 건너뛴다.
 * 실행: CDC_POC=true ./gradlew test --tests '*OutboxCdcPocTest'
 * 기존 AbstractConcurrencyIntegrationTest의 공유 컨테이너는 쓰지 않고 완전히 격리한다(ADR-0004 실험 설계).
 */
@EnabledIfEnvironmentVariable(named = "CDC_POC", matches = "true")
class OutboxCdcPocTest {

    private static final int SAMPLES = 20;
    private static final String TOPIC = "poc.poc.outbox_event";

    @Test
    void outbox_insert가_CDC로_Kafka_토픽에_도달하고_지연을_측정한다() throws Exception {
        try (Network network = Network.newNetwork();
             MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
                 .withNetwork(network).withNetworkAliases("mysql")
                 .withDatabaseName("poc").withUsername("root").withPassword("test")
                 .withCommand("--server-id=1", "--log-bin=mysql-bin", "--binlog-format=ROW", "--gtid-mode=ON",
                     "--enforce-gtid-consistency=ON");
             KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                 .withNetwork(network).withNetworkAliases("kafka");
             GenericContainer<?> connect = new GenericContainer<>(DockerImageName.parse("quay.io/debezium/connect:2.7"))
                 .withNetwork(network)
                 .withEnv("BOOTSTRAP_SERVERS", "kafka:9092")
                 .withEnv("GROUP_ID", "poc-connect")
                 .withEnv("CONFIG_STORAGE_TOPIC", "poc_configs")
                 .withEnv("OFFSET_STORAGE_TOPIC", "poc_offsets")
                 .withEnv("STATUS_STORAGE_TOPIC", "poc_statuses")
                 .withExposedPorts(8083)
                 .waitingFor(Wait.forHttp("/connectors").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))) {
            mysql.start();
            kafka.start();
            connect.start();

            try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
                 Statement s = c.createStatement()) {
                s.execute("create table outbox_event (id bigint primary key, status varchar(20) not null)");
            }
            registerConnector(connect);

            List<Long> latenciesMs = new ArrayList<>();
            try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
                 KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps(kafka))) {
                consumer.subscribe(Collections.singletonList(TOPIC));
                // 커넥터 스냅샷/토픽 생성 대기용 워밍업 이벤트 (측정에서 제외)
                insert(c, 0);
                awaitEvent(consumer, 0, Duration.ofSeconds(120));

                for (long id = 1; id <= SAMPLES; id++) {
                    long committedAt = insert(c, id);
                    long arrivedAt = awaitEvent(consumer, id, Duration.ofSeconds(30));
                    latenciesMs.add((arrivedAt - committedAt) / 1_000_000);
                }
            }

            Collections.sort(latenciesMs);
            long p50 = latenciesMs.get(SAMPLES / 2);
            long p95 = latenciesMs.get((int) Math.ceil(SAMPLES * 0.95) - 1);
            System.out.printf("[CDC-POC] n=%d p50=%dms p95=%dms all=%s%n", SAMPLES, p50, p95, latenciesMs);

            assertThat(latenciesMs).hasSize(SAMPLES);
            assertThat(p95).isLessThan(10_000); // 느슨한 상한선만 assert (ADR-0004)
        }
    }

    /** 커밋 직후의 nanoTime을 반환한다(autocommit). */
    private static long insert(Connection c, long id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("insert into outbox_event (id, status) values (?, 'PENDING')")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        return System.nanoTime();
    }

    private static long awaitEvent(KafkaConsumer<String, String> consumer, long id, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(50))) {
                if (r.key() != null && r.key().contains("\"id\":" + id + "}")) {
                    return System.nanoTime();
                }
            }
        }
        throw new AssertionError("outbox id=" + id + " 이벤트가 " + timeout + " 내에 토픽에 도달하지 않음");
    }

    private static Properties consumerProps(KafkaContainer kafka) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "poc-consumer");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return p;
    }

    private static void registerConnector(GenericContainer<?> connect) throws Exception {
        String body = """
            {"name":"poc-outbox","config":{
              "connector.class":"io.debezium.connector.mysql.MySqlConnector",
              "database.hostname":"mysql","database.port":"3306",
              "database.user":"root","database.password":"test",
              "database.server.id":"5400","topic.prefix":"poc",
              "database.include.list":"poc","table.include.list":"poc.outbox_event",
              "schema.history.internal.kafka.bootstrap.servers":"kafka:9092",
              "schema.history.internal.kafka.topic":"poc-schema-history"}}
            """;
        HttpResponse<String> res = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://" + connect.getHost() + ":" + connect.getMappedPort(8083) + "/connectors"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isBetween(200, 299);
    }
}

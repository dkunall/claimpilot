package com.claimpilot.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/** One of each dependency per JVM, started on first use. Credentials are random per run and never printed. */
public final class Containers {

    public static final String DB_PASSWORD = hex(18);
    public static final String S3_ACCESS_KEY = "it" + hex(8);
    public static final String S3_SECRET_KEY = hex(20);

    private static PostgreSQLContainer<?> postgres;
    private static KafkaContainer kafka;
    private static GenericContainer<?> mailpit;
    private static GenericContainer<?> s3;

    private Containers() {
    }

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            var pg = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("claimpilot")
                    .withUsername("claimpilot").withPassword(DB_PASSWORD).withCommand("postgres", "-c", "max_connections=200");
            pg.start();
            postgres = pg;
        }
        return postgres;
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            var k = new KafkaContainer("apache/kafka:3.8.0").withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");
            k.start();
            kafka = k;
        }
        return kafka;
    }

    public static synchronized GenericContainer<?> mailpit() {
        if (mailpit == null) {
            GenericContainer<?> m = new GenericContainer<>("axllent/mailpit:latest").withExposedPorts(1025, 8025)
                    .waitingFor(Wait.forHttp("/livez").forPort(8025));
            m.start();
            mailpit = m;
        }
        return mailpit;
    }

    public static String mailpitApi() {
        return "http://" + mailpit().getHost() + ":" + mailpit().getMappedPort(8025);
    }

    /** RustFS, an S3-compatible object store, standing in for S3. */
    public static synchronized GenericContainer<?> s3() {
        if (s3 == null) {
            GenericContainer<?> c = new GenericContainer<>("rustfs/rustfs:1.0.4").withExposedPorts(9000)
                    .withEnv("RUSTFS_ACCESS_KEY", S3_ACCESS_KEY).withEnv("RUSTFS_SECRET_KEY", S3_SECRET_KEY)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)));
            c.start();
            s3 = c;
        }
        return s3;
    }

    public static String s3Endpoint() {
        return "http://" + s3().getHost() + ":" + s3().getMappedPort(9000);
    }

    private static String hex(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}

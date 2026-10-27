package com.ken.platform.foundation.messaging;

import static org.assertj.core.api.Assertions.*;

import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.rabbitmq.client.Channel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_DATABASE_BINDING", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_RABBIT_BINDING", matches = ".+")
class RabbitEventsTest {
  private CachingConnectionFactory connections;
  private HikariDataSource pool;
  private JdbcTemplate jdbc;
  private TenantTransactions transactions;
  private RabbitEvents events;
  private Channel channel;
  private String queue;
  private final UUID tenant = UUID.randomUUID();

  @BeforeEach
  void setup() throws Exception {
    var json = JsonMapper.builder().build();
    var db =
        json.readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_DATABASE_BINDING"))));
    var mq =
        json.readTree(Files.readString(Path.of(System.getenv("PLATFORM_TEST_RABBIT_BINDING"))));
    assertThat(db.get("test_only").asBoolean()).isTrue();
    assertThat(mq.get("test_only").asBoolean()).isTrue();
    assertThat(db.get("dbname").asString()).startsWith("t02t");
    assertThat(mq.get("host").asString()).isEqualTo("127.0.0.1");
    var config = new HikariConfig();
    config.setJdbcUrl(
        "jdbc:postgresql://"
            + db.get("host").asString()
            + ":"
            + db.get("port").asInt()
            + "/"
            + db.get("dbname").asString());
    config.setUsername(db.get("username").asString());
    config.setPassword(Files.readString(Path.of(db.get("password_file").asString())).strip());
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    var manager = new JdbcTransactionManager(pool);
    transactions = new TenantTransactions(jdbc, manager);
    connections = new CachingConnectionFactory(mq.get("host").asString(), mq.get("port").asInt());
    connections.setUsername(mq.get("username").asString());
    connections.setPassword(Files.readString(Path.of(mq.get("password_file").asString())).strip());
    connections.setVirtualHost(mq.get("vhost").asString());
    connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    connections.setPublisherReturns(true);
    queue = "slim-test-" + UUID.randomUUID();
    events =
        new RabbitEvents(
            connections,
            jdbc,
            manager,
            queue,
            "sample-service",
            "sample.changed",
            Set.of(mq.get("username").asString()));
    events.declare(new RabbitAdmin(connections));
    channel = connections.createConnection().createChannel(false);
  }

  @AfterEach
  void close() throws Exception {
    if (connections != null) {
      var admin = new RabbitAdmin(connections);
      for (String suffix : Set.of("", ".quarantine", ".retry.1", ".retry.2", ".retry.3")) {
        admin.deleteQueue(queue + suffix);
        admin.deleteExchange(queue + suffix);
      }
      connections.destroy();
    }
    if (pool != null) pool.close();
  }

  private Event event(int version) {
    return new Event(
        UUID.randomUUID(),
        "sample.changed",
        version,
        tenant,
        "sample-service",
        Instant.now(),
        null,
        null,
        null,
        Map.of("amount", 1));
  }

  private Message take(String name) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
    while (System.nanoTime() < deadline) {
      var response = channel.basicGet(name, false);
      if (response != null)
        return new Message(
            response.getBody(),
            new DefaultMessagePropertiesConverter()
                .toMessageProperties(response.getProps(), response.getEnvelope(), "UTF-8"));
      Thread.sleep(100);
    }
    throw new AssertionError("未收到预期测试消息");
  }

  private void effect(Event event) {
    jdbc.update(
        "INSERT INTO slim_message_effects(event_id,tenant_id,amount) VALUES(?,?,1)",
        event.eventId(),
        event.tenantId());
  }

  private long count(UUID owner, Event event) {
    try (var ignored =
        CallContexts.bind(new CallContext("mq-test", owner, null, null, null, null))) {
      return transactions.execute(
          () ->
              jdbc.queryForObject(
                  "SELECT count(*) FROM slim_message_effects WHERE event_id=?",
                  Long.class,
                  event.eventId()));
    }
  }

  @Test
  void transactionRollbackDuplicateAndTenantIsolation() throws Exception {
    var aborted = event(1);
    try (var ignored =
        CallContexts.bind(new CallContext("mq-test", tenant, null, null, null, null))) {
      assertThatThrownBy(
              () ->
                  transactions.execute(
                      () -> {
                        events.enqueue(aborted);
                        throw new IllegalStateException("rollback");
                      }))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(events.dispatchOne(tenant)).isFalse();
    var value = event(1);
    try (var ignored =
        CallContexts.bind(new CallContext("mq-test", tenant, null, null, null, null))) {
      transactions.execute(
          () -> {
            events.enqueue(value);
            return null;
          });
    }
    assertThat(events.dispatchOne(tenant)).isTrue();
    assertThat(events.dispatchOne(tenant)).isFalse();
    events.publish(value.encode(), queue, 0);
    assertThat(events.consume(take(queue), channel, "sample-consumer", Set.of(1), this::effect))
        .isEqualTo("processed");
    assertThat(events.consume(take(queue), channel, "sample-consumer", Set.of(1), this::effect))
        .isEqualTo("duplicate");
    assertThat(count(tenant, value)).isEqualTo(1);
    assertThat(count(UUID.randomUUID(), value)).isZero();
  }

  @Test
  void handlerRollbackDelayedRetryAndExhaustion() throws Exception {
    var value = event(1);
    events.publish(value.encode(), queue, 0);
    assertThat(
            events.consume(
                take(queue),
                channel,
                "sample-consumer",
                Set.of(1),
                event -> {
                  effect(event);
                  throw new IllegalStateException("rollback");
                }))
        .isEqualTo("retry.1");
    assertThat(count(tenant, value)).isZero();
    assertThat(events.consume(take(queue), channel, "sample-consumer", Set.of(1), this::effect))
        .isEqualTo("processed");
    var exhausted = event(1);
    events.publish(exhausted.encode(), queue, 3);
    assertThat(
            events.consume(
                take(queue),
                channel,
                "sample-consumer",
                Set.of(1),
                event -> {
                  effect(event);
                  throw new IllegalStateException("rollback");
                }))
        .isEqualTo("quarantine");
    var isolated = take(queue + ".quarantine");
    assertThat(Event.decode(isolated.getBody()).eventId()).isEqualTo(exhausted.eventId());
    channel.basicAck(isolated.getMessageProperties().getDeliveryTag(), false);
    assertThat(count(tenant, exhausted)).isZero();
  }

  @Test
  void unknownVersionAndUnroutableMessagesCannotBeReportedAsProcessed() throws Exception {
    var value = event(2);
    events.publish(value.encode(), queue, 0);
    assertThat(events.consume(take(queue), channel, "sample-consumer", Set.of(1), this::effect))
        .isEqualTo("quarantine");
    assertThat(count(tenant, value)).isZero();
    var empty = queue + ".empty";
    var admin = new RabbitAdmin(connections);
    admin.declareExchange(new org.springframework.amqp.core.DirectExchange(empty, false, true));
    assertThatThrownBy(() -> events.publish(event(1).encode(), empty, 0))
        .isInstanceOf(RuntimeException.class);
    admin.deleteExchange(empty);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "PLATFORM_TEST_PYTHON", matches = ".+")
  void realJavaPythonMessagesUseSameEnvelopeAndTransactionalInbox() throws Exception {
    Event value = event(1);
    events.publish(value.encode(), queue, 0);
    var output = Path.of("target/messaging-peer-output.txt");
    var process =
        new ProcessBuilder(
                System.getenv("PLATFORM_TEST_PYTHON"),
                "-X",
                "utf8",
                Path.of("../python-sdk/tests/messaging_peer.py").toAbsolutePath().toString(),
                queue,
                value.eventId().toString())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    boolean exited = process.waitFor(30, TimeUnit.SECONDS);
    if (!exited) process.destroyForcibly();
    assertThat(exited).isTrue();
    assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
    assertThat(count(tenant, value)).isEqualTo(1);
    Message reply = take(queue);
    Event response = Event.decode(reply.getBody());
    assertThat(response.data()).containsEntry("source", "python");
    assertThat(events.consume(reply, channel, "java-peer", Set.of(1), this::effect))
        .isEqualTo("processed");
    assertThat(count(tenant, response)).isEqualTo(1);
  }

  @Test
  void confirmedPublishWithDatabaseFailureCanRetryWithoutDuplicateEffect() throws Exception {
    Event value = event(1);
    try (var ignored =
        CallContexts.bind(new CallContext("mq-test", tenant, null, null, null, null))) {
      transactions.execute(
          () -> {
            events.enqueue(value);
            return null;
          });
    }
    var failing = org.mockito.Mockito.spy(jdbc);
    org.mockito.Mockito.doThrow(
            new org.springframework.dao.DataAccessResourceFailureException("after confirm"))
        .when(failing)
        .update(
            org.mockito.ArgumentMatchers.eq(
                "UPDATE framework_outbox SET delivered_at=now() WHERE tenant_id=? AND event_id=?"),
            org.mockito.ArgumentMatchers.eq(tenant),
            org.mockito.ArgumentMatchers.any());
    var uncertain =
        new RabbitEvents(
            connections,
            failing,
            new JdbcTransactionManager(pool),
            queue,
            "sample-service",
            "sample.changed",
            Set.of(connections.getUsername()));
    assertThatThrownBy(() -> uncertain.dispatchOne(tenant))
        .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    assertThat(events.dispatchOne(tenant)).isTrue();
    assertThat(events.consume(take(queue), channel, "sample-consumer", Set.of(1), this::effect))
        .isEqualTo("processed");
    assertThat(events.consume(take(queue), channel, "sample-consumer", Set.of(1), this::effect))
        .isEqualTo("duplicate");
    assertThat(count(tenant, value)).isEqualTo(1);
  }
}

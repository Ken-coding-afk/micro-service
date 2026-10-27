package com.ken.platform.foundation.messaging;

import com.ken.platform.foundation.data.TenantTransactions;
import com.ken.platform.foundation.http.CallContext;
import com.ken.platform.foundation.http.CallContexts;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 可选 AMQP/JDBC 适配；不自动声明拓扑、不扫描租户、不启动 Worker。 */
public final class RabbitEvents {
  public static final int[] RETRY_SECONDS = {5, 30, 120};
  private final RabbitTemplate rabbit;
  private final JdbcTemplate jdbc;
  private final TenantTransactions transactions;
  private final String queue, producer, eventType, username;
  private final Set<String> acceptedUsers;

  public RabbitEvents(
      CachingConnectionFactory connections,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      String queue,
      String producer,
      String eventType,
      Set<String> acceptedUsers) {
    if (!queue.matches("[a-z][a-z0-9._-]{0,127}")
        || acceptedUsers.isEmpty()
        || !connections.isPublisherConfirms()
        || !connections.isPublisherReturns())
      throw new IllegalArgumentException("队列须配置可信账号、CORRELATED 确认和 returns");
    rabbit = new RabbitTemplate(connections);
    rabbit.setMandatory(true);
    this.jdbc = jdbc;
    transactions = new TenantTransactions(jdbc, manager);
    this.queue = queue;
    this.producer = producer;
    this.eventType = eventType;
    username = connections.getUsername();
    this.acceptedUsers = Set.copyOf(acceptedUsers);
  }

  /** 必须由获得拓扑配置权限的初始化进程显式调用。 */
  public void declare(RabbitAdmin admin) {
    for (String suffix : Set.of("", ".quarantine", ".retry.1", ".retry.2", ".retry.3"))
      admin.declareExchange(new DirectExchange(queue + suffix, true, false));
    admin.declareQueue(
        new Queue(
            queue + ".quarantine",
            true,
            false,
            false,
            Map.of("x-queue-type", "quorum", "x-delivery-limit", -1)));
    admin.declareQueue(
        new Queue(
            queue,
            true,
            false,
            false,
            Map.of(
                "x-queue-type",
                "quorum",
                "x-delivery-limit",
                20,
                "x-dead-letter-exchange",
                queue + ".quarantine",
                "x-dead-letter-routing-key",
                queue + ".quarantine",
                "x-dead-letter-strategy",
                "at-least-once",
                "x-overflow",
                "reject-publish")));
    for (int i = 0; i < RETRY_SECONDS.length; i++) {
      admin.declareQueue(
          new Queue(
              queue + ".retry." + (i + 1),
              true,
              false,
              false,
              Map.of(
                  "x-queue-type",
                  "quorum",
                  "x-message-ttl",
                  RETRY_SECONDS[i] * 1000,
                  "x-dead-letter-exchange",
                  queue,
                  "x-dead-letter-routing-key",
                  queue,
                  "x-dead-letter-strategy",
                  "at-least-once",
                  "x-overflow",
                  "reject-publish")));
    }
    bind(admin);
  }

  private void bind(RabbitAdmin admin) {
    for (String suffix : Set.of("", ".quarantine", ".retry.1", ".retry.2", ".retry.3")) {
      String name = queue + suffix;
      admin.declareBinding(new Binding(name, Binding.DestinationType.QUEUE, name, name, null));
    }
  }

  public void enqueue(Event event) {
    var context = CallContexts.current();
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || context == null
        || !event.tenantId().equals(context.tenantId())
        || !event
            .tenantId()
            .toString()
            .equals(
                jdbc.queryForObject("SELECT current_setting('app.tenant_id',true)", String.class)))
      throw new IllegalStateException("Outbox 必须加入当前租户业务事务");
    jdbc.update(
        "INSERT INTO framework_outbox(event_id,tenant_id,producer,event_type,body) VALUES(?,?,?,?,?)",
        event.eventId(),
        event.tenantId(),
        event.producer(),
        event.eventType(),
        new String(event.encode(), StandardCharsets.UTF_8));
  }

  public void publish(byte[] raw, String destination, int attempt) {
    var properties = new MessageProperties();
    properties.setContentType("application/json");
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setUserId(username); // Rabbit 验证该属性与连接账号匹配，禁止赋予 impersonator 标签。
    properties.setHeader("x-framework-attempt", attempt);
    var correlation = new CorrelationData(UUID.randomUUID().toString());
    rabbit.send(destination, destination, new Message(raw, properties), correlation);
    try {
      var confirmation = correlation.getFuture().get(5, TimeUnit.SECONDS);
      if (!confirmation.ack() || correlation.getReturned() != null)
        throw new IllegalStateException("消息未路由或发布确认失败");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("消息确认被中断", interrupted);
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException failure) {
      throw new IllegalStateException("消息发布确认不可用", failure);
    }
  }

  public boolean dispatchOne(UUID tenant) {
    try (var ignored =
        CallContexts.bind(
            new CallContext(UUID.randomUUID().toString(), tenant, null, null, null, null))) {
      return transactions.execute(
          () -> {
            var rows =
                jdbc.queryForList(
                    "SELECT event_id,body FROM framework_outbox WHERE tenant_id=? AND producer=? AND event_type=? AND delivered_at IS NULL ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED",
                    tenant,
                    producer,
                    eventType);
            if (rows.isEmpty()) return false;
            var row = rows.getFirst();
            byte[] raw = row.get("body").toString().getBytes(StandardCharsets.UTF_8);
            Event event = Event.decode(raw);
            if (!producer.equals(event.producer()) || !eventType.equals(event.eventType()))
              throw new IllegalStateException("Outbox 事件不属于当前投递通道");
            publish(raw, queue, 0);
            jdbc.update(
                "UPDATE framework_outbox SET delivered_at=now() WHERE tenant_id=? AND event_id=?",
                tenant,
                row.get("event_id"));
            return true;
          });
    }
  }

  /** 必须以 MANUAL ack 调用；handler 使用同一个事务管理器和数据源，不得自行提交。 */
  public String consume(
      Message message,
      Channel channel,
      String consumerName,
      Set<Integer> versions,
      Consumer<Event> handler)
      throws Exception {
    if (!consumerName.matches("[a-z][a-z0-9._-]{0,127}"))
      throw new IllegalArgumentException("consumer_name 不合法");
    Object header =
        message.getMessageProperties().getHeaders().getOrDefault("x-framework-attempt", 0);
    if (!(header instanceof Integer
            || header instanceof Long
            || header instanceof Short
            || header instanceof Byte)
        || ((Number) header).longValue() < 0
        || ((Number) header).longValue() > RETRY_SECONDS.length)
      return move(message, channel, "quarantine", 0);
    int attempt = ((Number) header).intValue();
    Event event;
    try {
      event = Event.decode(message.getBody());
      if (!acceptedUsers.contains(message.getMessageProperties().getReceivedUserId())
          || !producer.equals(event.producer())
          || !eventType.equals(event.eventType())
          || !versions.contains(event.schemaVersion()))
        throw new IllegalArgumentException("未知事件或来源");
    } catch (RuntimeException invalid) {
      return move(message, channel, "quarantine", attempt);
    }
    boolean inserted;
    try (var ignored =
        CallContexts.bind(
            new CallContext(
                UUID.randomUUID().toString(),
                event.tenantId(),
                null,
                null,
                event.traceparent(),
                null))) {
      inserted =
          transactions.execute(
              () -> {
                String digest;
                try {
                  digest =
                      HexFormat.of()
                          .formatHex(
                              MessageDigest.getInstance("SHA-256").digest(message.getBody()));
                } catch (java.security.NoSuchAlgorithmException impossible) {
                  throw new IllegalStateException(impossible);
                }
                int count =
                    jdbc.update(
                        "INSERT INTO framework_inbox(consumer_name,event_id,tenant_id,body_digest) VALUES(?,?,?,?) ON CONFLICT (consumer_name,event_id) DO NOTHING",
                        consumerName,
                        event.eventId(),
                        event.tenantId(),
                        digest);
                if (count == 1) handler.accept(event);
                else {
                  var previous =
                      jdbc.queryForList(
                          "SELECT body_digest FROM framework_inbox WHERE consumer_name=? AND event_id=? AND tenant_id=?",
                          String.class,
                          consumerName,
                          event.eventId(),
                          event.tenantId());
                  if (previous.size() != 1 || !previous.getFirst().equals(digest))
                    throw new IllegalArgumentException("事件标识与已处理内容冲突");
                }
                return count == 1;
              });
    } catch (RuntimeException failure) {
      return move(
          message,
          channel,
          attempt < RETRY_SECONDS.length ? "retry." + (attempt + 1) : "quarantine",
          Math.min(attempt + 1, RETRY_SECONDS.length));
    }
    channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    return inserted ? "processed" : "duplicate";
  }

  private String move(Message message, Channel channel, String suffix, int attempt)
      throws Exception {
    try {
      publish(message.getBody(), queue + "." + suffix, attempt);
    } catch (RuntimeException failure) {
      channel.close();
      throw failure;
    }
    channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    if (suffix.equals("quarantine"))
      org.slf4j.LoggerFactory.getLogger(RabbitEvents.class)
          .error("event_quarantined queue={} reason=invalid_or_exhausted", queue);
    return suffix;
  }
}

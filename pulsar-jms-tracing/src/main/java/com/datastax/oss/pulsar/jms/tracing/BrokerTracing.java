/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.pulsar.jms.tracing;

import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.EventReasons;
import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.TraceLevel;
import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.getCommandDetails;
import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.getConnectionDetails;
import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.getConsumerDetails;
import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.getEntryDetails;
import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.getMessageMetadataDetails;
import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.getProducerDetails;
import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.getPublishContextDetails;
import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.getSubscriptionDetails;
import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.trace;
import static com.datastax.oss.pulsar.jms.tracing.TracingUtils.traceByteBuf;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.intercept.BrokerInterceptor;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.Producer;
import org.apache.pulsar.broker.service.ServerCnx;
import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.common.api.proto.BaseCommand;
import org.apache.pulsar.common.api.proto.CommandAck;
import org.apache.pulsar.common.api.proto.MessageIdData;
import org.apache.pulsar.common.api.proto.MessageMetadata;
import org.apache.pulsar.common.intercept.InterceptException;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.stats.ConsumerStatsImpl;
import org.apache.pulsar.common.policies.data.stats.PublisherStatsImpl;
import org.apache.pulsar.common.util.DateFormatter;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class BrokerTracing implements BrokerInterceptor {

  private static final TraceLevel defaultTraceLevel = TraceLevel.OFF;

  private final Set<EventReasons> jmsTracingEventList = new HashSet<>();
  private TraceLevel traceLevel = defaultTraceLevel;
  private int maxBinaryDataLength = 256;
  private int cacheTraceLevelsDurationSec = 10;
  private boolean traceSystemTopics = false;
  private boolean traceSchema = false;

  private static void loadEnabledEvents(
      PulsarService pulsarService, Set<EventReasons> enabledEvents) {
    Properties props = pulsarService.getConfiguration().getProperties();
    if (props.contains("jmsTracingEventList")) {
      String events = props.getProperty("jmsTracingEventList", "");
      log.debug("read jmsTracingEventList: {}", events);

      enabledEvents.clear();
      for (String event : events.split(",")) {
        try {
          enabledEvents.add(EventReasons.valueOf(event.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
          log.error("Invalid event: {}. Skipping", event);
        }
      }
    } else {
      log.warn("jmsTracingEventList not set. Using all available.");
      enabledEvents.addAll(Arrays.asList(EventReasons.values()));
    }
  }

  @NotNull
  private static TraceLevel getTraceLevel(PulsarService pulsar) {
    String level =
        pulsar
            .getConfiguration()
            .getProperties()
            .getProperty("jmsTracingLevel", defaultTraceLevel.toString());
    try {
      return TraceLevel.valueOf(level.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      log.warn("Invalid tracing level: {}. Using default: {}", level, defaultTraceLevel);
      return defaultTraceLevel;
    }
  }

  private final LoadingCache<Subscription, TraceLevel> traceLevelForSubscription =
      CacheBuilder.newBuilder()
          .maximumSize(10_000L)
          .concurrencyLevel(Runtime.getRuntime().availableProcessors())
          .expireAfterWrite(10L * cacheTraceLevelsDurationSec, TimeUnit.SECONDS)
          .refreshAfterWrite(cacheTraceLevelsDurationSec, TimeUnit.SECONDS)
          .build(
              new CacheLoader<Subscription, TraceLevel>() {
                public TraceLevel load(Subscription sub) {
                  log.info("Loading trace level for subscription {}", sub);
                  return BrokerTracing.readTraceLevelForSubscription(sub);
                }

                public ListenableFuture<TraceLevel> reload(Subscription sub, TraceLevel oldValue)
                    throws Exception {
                  SettableFuture<TraceLevel> future = SettableFuture.create();
                  BrokerTracing.readTraceLevelForSubscriptionAsync(sub)
                      .whenComplete(
                          (level, ex) -> {
                            if (ex != null) {
                              future.setException(ex);
                            } else {
                              future.set(level);
                            }
                          });
                  return future;
                }
              });
  private final LoadingCache<Producer, TraceLevel> traceLevelForProducer =
      CacheBuilder.newBuilder()
          .maximumSize(10_000L)
          .concurrencyLevel(Runtime.getRuntime().availableProcessors())
          .expireAfterWrite(10L * cacheTraceLevelsDurationSec, TimeUnit.SECONDS)
          .refreshAfterWrite(cacheTraceLevelsDurationSec, TimeUnit.SECONDS)
          .build(
              new CacheLoader<Producer, TraceLevel>() {
                public TraceLevel load(Producer producer) {
                  try {
                    log.info("Loading trace level for producer {}", producer);
                    PulsarAdmin admin =
                        producer.getCnx().getBrokerService().getPulsar().getAdminClient();
                    Topic topic = producer.getTopic();

                    return BrokerTracing.readTraceLevelForTopic(admin, topic);
                  } catch (Throwable t) {
                    log.error("Error getting tracing level", t);
                    return TraceLevel.OFF;
                  }
                }

                public ListenableFuture<TraceLevel> reload(Producer producer, TraceLevel oldValue)
                    throws Exception {
                  SettableFuture<TraceLevel> future = SettableFuture.create();

                  PulsarAdmin admin =
                      producer.getCnx().getBrokerService().getPulsar().getAdminClient();
                  Topic topic = producer.getTopic();

                  BrokerTracing.readTraceLevelForTopicAsync(admin, topic)
                      .whenComplete(
                          (level, ex) -> {
                            if (ex != null) {
                              future.setException(ex);
                            } else {
                              future.set(level);
                            }
                          });

                  return future;
                }
              });

  @NotNull
  private static TraceLevel readTraceLevelForSubscription(Subscription sub) {
    try {
      return readTraceLevelForSubscriptionAsync(sub).get();
    } catch (InterruptedException | ExecutionException e) {
      log.error("Interrupted while getting subscription tracing level for {}", sub, e);
      Thread.currentThread().interrupt();
      return TraceLevel.OFF;
    } catch (Throwable t) {
      log.error("Error getting subscription tracing level for {}", sub, t);
      return TraceLevel.OFF;
    }
  }

  @NotNull
  private static CompletableFuture<TraceLevel> readTraceLevelForSubscriptionAsync(
      Subscription sub) {
    Map<String, String> subProps = sub.getSubscriptionProperties();
    try {
      if (subProps == null || !subProps.containsKey("trace")) {
        PulsarAdmin admin = sub.getTopic().getBrokerService().getPulsar().getAdminClient();
        return BrokerTracing.readTraceLevelForTopicAsync(admin, sub.getTopic());
      }

      return CompletableFuture.completedFuture(
          TraceLevel.valueOf(subProps.get("trace").trim().toUpperCase()));
    } catch (IllegalArgumentException e) {
      log.warn(
          "Invalid tracing level: {}. Setting to NONE for subscription {}",
          subProps.get("trace"),
          sub);
      return CompletableFuture.completedFuture(TraceLevel.OFF);
    } catch (Throwable t) {
      log.error("Error getting tracing level. Setting to NONE for subscription {}", sub, t);
      return CompletableFuture.completedFuture(TraceLevel.OFF);
    }
  }

  @NotNull
  private static TraceLevel readTraceLevelForTopic(PulsarAdmin admin, Topic topic) {
    try {
      return readTraceLevelForTopicAsync(admin, topic).get();
    } catch (InterruptedException | ExecutionException e) {
      log.error("Interrupted while getting tracing level for topic {}", topic.getName(), e);
      Thread.currentThread().interrupt();
      return TraceLevel.OFF;
    } catch (Throwable t) {
      log.error("Error getting tracing level for topic {}", topic.getName(), t);
      return TraceLevel.OFF;
    }
  }

  @NotNull
  private static CompletableFuture<TraceLevel> readTraceLevelForTopicAsync(
      PulsarAdmin admin, Topic topic) {
    CompletableFuture<Map<String, String>> propsFuture =
        admin.topics().getPropertiesAsync(TopicName.get(topic.getName()).getPartitionedTopicName());
    return propsFuture.handle(
        (props, ex) -> {
          if (ex != null) {
            log.error("Error getting tracing level for topic {}", topic.getName(), ex);
            return TraceLevel.OFF;
          }

          try {
            if (props == null || !props.containsKey("trace")) {
              return TraceLevel.OFF;
            }

            return TraceLevel.valueOf(props.get("trace").trim().toUpperCase());
          } catch (IllegalArgumentException e) {
            log.warn(
                "Invalid tracing level for topic {}: {}. Setting to NONE",
                topic.getName(),
                props.get("trace"));
            return TraceLevel.OFF;
          }
        });
  }

  public void initialize(PulsarService pulsarService) {
    log.info("Initializing BrokerTracing");

    loadEnabledEvents(pulsarService, jmsTracingEventList);
    traceLevel = getTraceLevel(pulsarService);

    Properties props = pulsarService.getConfiguration().getProperties();
    if (props.containsKey("jmsTracingMaxBinaryDataLength")) {
      maxBinaryDataLength = Integer.parseInt(props.getProperty("jmsTracingMaxBinaryDataLength"));
    }
    if (props.containsKey("jmsTracingTraceSystemTopics")) {
      traceSystemTopics = Boolean.parseBoolean(props.getProperty("jmsTracingTraceSystemTopics"));
    }
    if (props.containsKey("jmsTracingTraceSchema")) {
      traceSchema = Boolean.parseBoolean(props.getProperty("jmsTracingTraceSchema"));
    }
    if (props.containsKey("jmsTracingCacheTraceLevelsDurationSec")) {
      cacheTraceLevelsDurationSec =
          Integer.parseInt(props.getProperty("jmsTracingCacheTraceLevelsDurationSec"));
      if (cacheTraceLevelsDurationSec <= 0) {
        log.warn(
            "Invalid cache duration: {}. Setting to default: {}", cacheTraceLevelsDurationSec, 10);
        cacheTraceLevelsDurationSec = 10;
      }
    }
  }

  @Override
  public void close() {
    log.info("Closing BrokerTracing");
  }

  private TraceLevel getTracingLevel(Consumer consumer) {
    if (consumer == null) return TraceLevel.OFF;

    return getTracingLevel(consumer.getSubscription());
  }

  private TraceLevel getTracingLevel(Subscription sub) {
    if (sub == null) return TraceLevel.OFF;

    if (!traceSystemTopics && sub.getTopic().isSystemTopic()) return TraceLevel.OFF;

    try {
      return traceLevelForSubscription.get(sub);
    } catch (ExecutionException e) {
      log.error("Error getting tracing level", e);
      return TraceLevel.OFF;
    }
  }

  private TraceLevel getTracingLevel(Producer producer) {
    if (producer == null) return TraceLevel.OFF;

    if (!traceSystemTopics && producer.getTopic().isSystemTopic()) return TraceLevel.OFF;

    try {
      return traceLevelForProducer.get(producer);
    } catch (ExecutionException e) {
      log.error("Error getting tracing level", e);
      return TraceLevel.OFF;
    }
  }

  /* ***************************
   **  Administrative events
   ******************************/

  public void onConnectionCreated(ServerCnx cnx) {
    if (!jmsTracingEventList.contains(EventReasons.ADMINISTRATIVE)) return;

    if (traceLevel == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("serverCnx", getConnectionDetails(cnx));
    trace(EventReasons.ADMINISTRATIVE, "Connection created", traceDetails);
  }

  public void producerCreated(ServerCnx cnx, Producer producer, Map<String, String> metadata) {
    if (!jmsTracingEventList.contains(EventReasons.ADMINISTRATIVE)) return;
    if (!traceSystemTopics && producer.getTopic().isSystemTopic()) return;

    if (traceLevel == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("producer", getProducerDetails(producer, traceSchema));
    traceDetails.put("metadata", metadata);

    trace(EventReasons.ADMINISTRATIVE, "Producer created", traceDetails);
  }

  public void producerClosed(ServerCnx cnx, Producer producer, Map<String, String> metadata) {
    if (!jmsTracingEventList.contains(EventReasons.ADMINISTRATIVE)) return;
    if (!traceSystemTopics && producer.getTopic().isSystemTopic()) return;

    if (traceLevel == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("producer", getProducerDetails(producer, traceSchema));
    traceDetails.put("metadata", metadata);

    PublisherStatsImpl stats = producer.getStats();
    traceDetails.put("connectedSince", stats.getConnectedSince());
    traceDetails.put("closedAt", DateFormatter.now());
    traceDetails.put("averageMsgSize", stats.getAverageMsgSize());
    traceDetails.put("msgRateIn", stats.getMsgRateIn());
    traceDetails.put("msgThroughputIn", stats.getMsgThroughputIn());
    // no message count in stats? stats.getCount() is not it

    trace(EventReasons.ADMINISTRATIVE, "Producer closed", traceDetails);
  }

  public void consumerCreated(ServerCnx cnx, Consumer consumer, Map<String, String> metadata) {
    if (!jmsTracingEventList.contains(EventReasons.ADMINISTRATIVE)) return;
    if (!traceSystemTopics && consumer.getSubscription().getTopic().isSystemTopic()) return;

    if (traceLevel == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("consumer", getConsumerDetails(consumer));
    traceDetails.put("subscription", getSubscriptionDetails(consumer.getSubscription()));
    traceDetails.put("metadata", metadata);

    trace(EventReasons.ADMINISTRATIVE, "Consumer created", traceDetails);
  }

  public void consumerClosed(ServerCnx cnx, Consumer consumer, Map<String, String> metadata) {
    if (!jmsTracingEventList.contains(EventReasons.ADMINISTRATIVE)) return;
    if (!traceSystemTopics && consumer.getSubscription().getTopic().isSystemTopic()) return;

    if (traceLevel == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("consumer", getConsumerDetails(consumer));
    traceDetails.put("subscription", getSubscriptionDetails(consumer.getSubscription()));
    traceDetails.put("metadata", metadata);

    ConsumerStatsImpl stats = consumer.getStats();

    traceDetails.put("connectedSince", stats.getConnectedSince());
    traceDetails.put("closedAt", DateFormatter.now());
    traceDetails.put("averageMsgSize", stats.getAvgMessagesPerEntry());
    traceDetails.put("msgRateOut", stats.getMsgRateOut());
    traceDetails.put("msgThroughputOut", stats.getMsgThroughputOut());
    traceDetails.put("msgOutCounter", stats.getMsgOutCounter());
    traceDetails.put("bytesOutCounter", stats.getBytesOutCounter());
    traceDetails.put("unackedMessages", stats.getUnackedMessages());
    traceDetails.put("messageAckRate", stats.getMessageAckRate());

    trace(EventReasons.ADMINISTRATIVE, "Consumer closed", traceDetails);
  }

  public void onPulsarCommand(BaseCommand command, ServerCnx cnx) throws InterceptException {
    if (!jmsTracingEventList.contains(EventReasons.COMMANDS)) return;

    if (traceLevel == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("serverCnx", getConnectionDetails(cnx));

    if (command.hasType()) {
      traceDetails.put("type", command.getType().name());
      traceDetails.put("command", getCommandDetails(command));
    } else {
      traceDetails.put("type", "unknown/null");
    }

    trace(EventReasons.COMMANDS, "Pulsar command called", traceDetails);
  }

  public void onConnectionClosed(ServerCnx cnx) {
    if (!jmsTracingEventList.contains(EventReasons.ADMINISTRATIVE)) return;

    if (traceLevel == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("serverCnx", getConnectionDetails(cnx));

    trace(EventReasons.ADMINISTRATIVE, "Connection closed", traceDetails);
  }

  /* ***************************
   **  Message events
   ******************************/

  public void beforeSendMessage(
      Subscription subscription,
      Entry entry,
      long[] ackSet,
      MessageMetadata msgMetadata,
      Consumer consumer) {
    if (!jmsTracingEventList.contains(EventReasons.MESSAGE)) return;

    TraceLevel level = getTracingLevel(subscription);
    if (level == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("subscription", getSubscriptionDetails(subscription));
    traceDetails.put("consumer", getConsumerDetails(consumer));
    traceDetails.put("entry", getEntryDetails(entry, maxBinaryDataLength));
    traceDetails.put("messageMetadata", getMessageMetadataDetails(msgMetadata));

    trace(EventReasons.MESSAGE, "Message read", traceDetails);
  }

  public void onMessagePublish(
      Producer producer, ByteBuf headersAndPayload, Topic.PublishContext publishContext) {

    if (!jmsTracingEventList.contains(EventReasons.MESSAGE)) return;

    TraceLevel level = getTracingLevel(producer);
    if (level == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("producer", getProducerDetails(producer, traceSchema));
    traceDetails.put("publishContext", getPublishContextDetails(publishContext));

    Map<String, Object> headersAndPayloadDetails = new TreeMap<>();
    traceByteBuf(
        "headersAndPayload", headersAndPayload, headersAndPayloadDetails, maxBinaryDataLength);
    traceDetails.put("payload", headersAndPayloadDetails);

    trace(EventReasons.MESSAGE, "Message received", traceDetails);
  }

  public void messageProduced(
      ServerCnx cnx,
      Producer producer,
      long startTimeNs,
      long ledgerId,
      long entryId,
      Topic.PublishContext publishContext) {
    if (!jmsTracingEventList.contains(EventReasons.MESSAGE)) return;

    TraceLevel level = getTracingLevel(producer);
    if (level == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("serverCnx", getConnectionDetails(cnx));
    traceDetails.put("producer", getProducerDetails(producer, traceSchema));
    traceDetails.put("publishContext", getPublishContextDetails(publishContext));
    traceDetails.put("messageId", ledgerId + ":" + entryId);
    traceDetails.put("startTimeNs", startTimeNs);
    trace(EventReasons.MESSAGE, "Message stored", traceDetails);
  }

  public void messageDispatched(
      ServerCnx cnx, Consumer consumer, long ledgerId, long entryId, ByteBuf headersAndPayload) {
    if (!jmsTracingEventList.contains(EventReasons.MESSAGE)) return;

    TraceLevel level = getTracingLevel(consumer);
    if (level == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("consumer", getConsumerDetails(consumer));
    if (consumer != null) {
      traceDetails.put("subscription", getSubscriptionDetails(consumer.getSubscription()));
    }
    traceDetails.put("messageId", ledgerId + ":" + entryId);

    Map<String, Object> headersAndPayloadDetails = new TreeMap<>();
    traceByteBuf(
        "headersAndPayload", headersAndPayload, headersAndPayloadDetails, maxBinaryDataLength);
    traceDetails.put("payload", headersAndPayloadDetails);

    trace(EventReasons.MESSAGE, "Message dispatched", traceDetails);
  }

  public void messageAcked(ServerCnx cnx, Consumer consumer, CommandAck ackCmd) {
    if (!jmsTracingEventList.contains(EventReasons.MESSAGE)) return;

    TraceLevel level = getTracingLevel(consumer);
    if (level == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("consumer", getConsumerDetails(consumer));
    if (consumer != null) {
      traceDetails.put("subscription", getSubscriptionDetails(consumer.getSubscription()));
    }

    Map<String, Object> ackDetails = new TreeMap<>();
    if (ackCmd.hasAckType()) {
      ackDetails.put("type", ackCmd.getAckType().name());
    } else {
      ackDetails.put("type", "NOT SET");
    }
    if (ackCmd.hasConsumerId()) {
      ackDetails.put("consumerId", ackCmd.getConsumerId());
    } else {
      ackDetails.put("consumerId", "NOT SET");
    }
    ackDetails.put("numAckedMessages", ackCmd.getMessageIdsCount());
    ackDetails.put(
        "messageIds",
        ackCmd
            .getMessageIdsList()
            .stream()
            .map(BrokerTracing::formatMessageId)
            .collect(Collectors.toList()));

    if (ackCmd.hasTxnidLeastBits() && ackCmd.hasTxnidMostBits()) {
      ackDetails.put(
          "txnID", "(" + ackCmd.getTxnidMostBits() + "," + ackCmd.getTxnidLeastBits() + ")");
    }
    if (ackCmd.hasRequestId()) {
      ackDetails.put("requestId", ackCmd.getRequestId());
    }

    traceDetails.put("ack", ackDetails);

    trace(EventReasons.MESSAGE, "Message acked", traceDetails);
  }

  @NotNull
  private static String formatMessageId(MessageIdData x) {
    String msgId = x.getLedgerId() + ":" + x.getEntryId();
    if (x.hasBatchIndex()) {
      msgId += " (batchSize: " + x.getBatchSize() + "|ackSetCnt: " + x.getAckSetsCount() + ")";
    } else if (x.getAckSetsCount() > 0) {
      msgId += " (ackSetCnt " + x.getAckSetsCount() + ")";
    }
    return msgId;
  }

  /* ***************************
   **  Transaction events
   ******************************/

  public void txnOpened(long tcId, String txnID) {
    if (!jmsTracingEventList.contains(EventReasons.TRANSACTION)) return;
    if (traceLevel == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("tcId", tcId);
    traceDetails.put("txnID", txnID);

    trace(EventReasons.TRANSACTION, "Transaction opened", traceDetails);
  }

  public void txnEnded(String txnID, long txnAction) {
    if (!jmsTracingEventList.contains(EventReasons.TRANSACTION)) return;
    if (traceLevel == TraceLevel.OFF) return;

    Map<String, Object> traceDetails = new TreeMap<>();
    traceDetails.put("txnID", txnID);
    traceDetails.put("txnAction", txnAction);

    trace(EventReasons.TRANSACTION, "Transaction closed", traceDetails);
  }

  /* ***************************
   **  Servlet events
   ******************************/

  public void onWebserviceRequest(ServletRequest request)
      throws IOException, ServletException, InterceptException {
    //    if (getEnabledEvents(???).contains(EventReasons.SERVLET)) {
    //      log.info("onWebserviceRequest: Tracing servlet requests not supported");
    //    }
  }

  public void onWebserviceResponse(ServletRequest request, ServletResponse response)
      throws IOException, ServletException {
    //    if (getEnabledEvents(???).contains(EventReasons.SERVLET)) {
    //      log.info("onWebserviceResponse: Tracing servlet requests not supported");
    //    }
  }

  // not needed
  // public void onFilter(ServletRequest request, ServletResponse response, FilterChain chain)
}

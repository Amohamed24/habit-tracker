package com.habitflow.insights.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.habitflow.insights.event.EventEnvelope;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dead-letter handling for the habit-events listener (R1-12a / HF-19, fixes L7).
 *
 * <p>What reaches {@link KafkaConfig#DLT_TOPIC}, on the same partition number as the source record:
 * <ul>
 *   <li><b>Deserialization failures</b> (malformed JSON): {@code ErrorHandlingDeserializer} hands the record to the
 *       error handler; {@code DeserializationException} is not retryable in {@link DefaultErrorHandler}, so it is
 *       dead-lettered on the first attempt with the <b>original key and value bytes</b>.</li>
 *   <li><b>Apply failures</b> (the listener throws): dead-lettered after the default backoff is exhausted
 *       (10 attempts; tuning and classification are HF-20). The value is the deserialized {@link EventEnvelope}
 *       <b>re-serialized as JSON</b>, not the original bytes.</li>
 * </ul>
 * The recoverer waits for the DLT send and throws if it fails; the error handler then seeks back instead of
 * committing, so a record's offset is committed only after its DLT publish succeeded.
 */
@Configuration
public class DeadLetterConfig {

    /**
     * DLT-only template, from its own producer factory (insights-service has no other producer). Values are
     * serialized by runtime type: {@code byte[]} (original bytes of a record that failed deserialization),
     * {@code String}, and {@link EventEnvelope} (apply failures; without it the recoverer would throw and the
     * partition would never move on).
     */
    @Bean
    public KafkaTemplate<String, Object> deadLetterKafkaTemplate(KafkaProperties kafkaProperties,
                                                                ObjectProvider<SslBundles> sslBundles,
                                                                ObjectMapper objectMapper) {
        Map<String, Object> config = new LinkedHashMap<>(
                kafkaProperties.buildProducerProperties(sslBundles.getIfAvailable()));
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(config,
                new StringSerializer(), dltValueSerializer(objectMapper));
        return new KafkaTemplate<>(factory);
    }

    /** The DLT value serializer; static so DeadLetterSerializerTest can check it without a context. */
    static DelegatingByTypeSerializer dltValueSerializer(ObjectMapper objectMapper) {
        // Boot's ObjectMapper: ISO-8601 dates, the same text habit-service writes. No __TypeId__ header.
        JsonSerializer<Object> envelopeSerializer = new JsonSerializer<>(objectMapper);
        envelopeSerializer.setAddTypeInfo(false);

        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(String.class, new StringSerializer());
        byType.put(EventEnvelope.class, envelopeSerializer);
        return new DelegatingByTypeSerializer(byType);
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaTemplate<String, Object> deadLetterKafkaTemplate) {
        return new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaConfig.DLT_TOPIC, record.partition()));
    }

    /**
     * Picked up by Boot's listener container factory (a single {@code CommonErrorHandler} bean). Default backoff
     * on purpose: bounded exponential retry is HF-20.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer deadLetterPublishingRecoverer) {
        return new DefaultErrorHandler(deadLetterPublishingRecoverer);
    }
}

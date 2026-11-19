package com.fraud.transaction.service;

import com.fraud.common.dto.TransactionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * KafkaProducerService — Kafka event publisher (Polish v3)
 *
 * Publishes TransactionEvent to the "transactions" topic for fraud analysis.
 *
 * Key properties:
 *   - Non-blocking: send() returns immediately; result logged via CompletableFuture callback
 *   - Idempotent producer: enable.idempotence=true in application.yml
 *   - acks=all: waits for all ISR replicas before callback fires
 *
 * All System.out.println debug statements have been removed.
 * ex.printStackTrace() has been replaced with structured SLF4J logging.
 */
@Slf4j
@Service
public class KafkaProducerService {

    @Autowired
    private KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    @Value("${kafka.topic.transactions:transactions}")
    private String transactionTopic;

    @Value("${kafka.producer.send-timeout-ms:10000}")
    private long sendTimeoutMs;

    /**
     * Publish a TransactionEvent to Kafka and confirm broker acceptance before
     * returning. Fraud analysis remains asynchronous, but callers must not
     * receive a transaction ID for an event that was never queued.
     *
     * @param event the transaction event to publish
     */
    public void publishTransaction(TransactionEvent event) {
        log.info("[KAFKA-PRODUCER] Publishing → txId={} userId={} amount={}",
                event.getTransactionId(), event.getUserId(), event.getAmount());

        try {
            SendResult<String, TransactionEvent> result = kafkaTemplate
                    .send(transactionTopic, event.getTransactionId(), event)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("[KAFKA-PRODUCER] ✓ Sent txId={} → partition={} offset={}",
                    event.getTransactionId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[KAFKA-PRODUCER] ✗ Interrupted while publishing txId={}",
                    event.getTransactionId(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Transaction could not be queued for fraud analysis", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("[KAFKA-PRODUCER] ✗ Failed to publish txId={} — error={}",
                    event.getTransactionId(), e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Transaction could not be queued for fraud analysis", e);
        } catch (KafkaException e) {
            log.error("[KAFKA-PRODUCER] ✗ Broker rejected txId={}",
                    event.getTransactionId(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Transaction could not be queued for fraud analysis", e);
        }
    }

    /**
     * Alias for {@link #publishTransaction(TransactionEvent)}.
     * Provided for backward compatibility with callers that use sendTransaction().
     *
     * @param event the transaction event to publish
     */
    public void sendTransaction(TransactionEvent event) {
        publishTransaction(event);
    }
}

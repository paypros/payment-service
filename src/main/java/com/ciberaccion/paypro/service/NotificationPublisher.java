package com.ciberaccion.paypro.service;

import com.ciberaccion.paypro.dto.PaymentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Service
@Slf4j
public class NotificationPublisher {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    @Value("${aws.sqs.queue.url}")
    private String queueUrl;

    public NotificationPublisher(SqsClient sqsClient, ObjectMapper objectMapper) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
    }

    public void publish(PaymentEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(message)
                    .build());
            log.info("Event published to SQS: paymentId={} status={}",
                    event.getPaymentId(), event.getStatus());
        } catch (Exception e) {
            log.error("Failed to publish event to SQS: {}", e.getMessage());
        }
    }

    public void publishRaw(String payload) {
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(payload)
                    .build());
            log.info("Event published to SQS");
        } catch (Exception e) {
            log.error("Failed to publish to SQS: {}", e.getMessage());
            throw new RuntimeException("SQS publish failed", e);
        }
    }
}
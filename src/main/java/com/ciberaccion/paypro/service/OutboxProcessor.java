// src/main/java/com/ciberaccion/paypro/service/OutboxProcessor.java

package com.ciberaccion.paypro.service;

import com.ciberaccion.paypro.model.OutboxEvent;
import com.ciberaccion.paypro.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@EnableScheduling
public class OutboxProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationPublisher notificationPublisher;

    public OutboxProcessor(OutboxEventRepository outboxEventRepository,
            NotificationPublisher notificationPublisher) {
        this.outboxEventRepository = outboxEventRepository;
        this.notificationPublisher = notificationPublisher;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void process() {
        List<OutboxEvent> pending = outboxEventRepository.findByProcessedFalse();

        for (OutboxEvent event : pending) {
            try {
                notificationPublisher.publishRaw(event.getPayload());
                event.setProcessed(true);
                outboxEventRepository.save(event);
                log.info("Outbox event processed: paymentId={}", event.getPaymentId());
            } catch (Exception e) {
                log.error("Failed to process outbox event id={}: {}", event.getId(), e.getMessage());
            }
        }
    }
}
package com.ciberaccion.paypro.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.ciberaccion.paypro.dto.CardValidationRequest;
import com.ciberaccion.paypro.dto.CardValidationResponse;
import com.ciberaccion.paypro.dto.DebitRequest;
import com.ciberaccion.paypro.dto.PaymentEvent;
import com.ciberaccion.paypro.dto.PaymentRequest;
import com.ciberaccion.paypro.dto.PaymentResponse;
import com.ciberaccion.paypro.exception.PaymentNotFoundException;
import com.ciberaccion.paypro.model.OutboxEvent;
import com.ciberaccion.paypro.model.Payment;
import com.ciberaccion.paypro.model.PaymentStatus;
import com.ciberaccion.paypro.repository.OutboxEventRepository;
import com.ciberaccion.paypro.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ProviderServiceClient providerServiceClient;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepository,
                          OutboxEventRepository outboxEventRepository,
                          AccountServiceClient accountServiceClient,
                          ProviderServiceClient providerServiceClient,
                          ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.accountServiceClient = accountServiceClient;
        this.providerServiceClient = providerServiceClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentResponse create(PaymentRequest request) {
        Payment payment = new Payment();
        payment.setMerchant(request.getMerchant());
        payment.setAmount(request.getAmount());
        payment.setCurrency(request.getCurrency());
        payment.setCreatedAt(LocalDateTime.now());

        // 1. Validar tarjeta
        try {
            boolean approved = providerServiceClient.validate(request);
            if (!approved) {
                payment.setStatus(PaymentStatus.REJECTED);
                return saveWithOutbox(payment);
            }
        } catch (Exception e) {
            log.warn("Provider validation failed: {}", e.getMessage());
            payment.setStatus(PaymentStatus.REJECTED);
            return saveWithOutbox(payment);
        }

        // 2. Debitar cuenta
        try {
            accountServiceClient.debit(request);
            payment.setStatus(PaymentStatus.APPROVED);
        } catch (Exception e) {
            log.warn("Account debit failed: {}", e.getMessage());
            payment.setStatus(PaymentStatus.REJECTED);
        }

        return saveWithOutbox(payment);
    }

    private PaymentResponse saveWithOutbox(Payment payment) {
        Payment saved = paymentRepository.save(payment);

        try {
            PaymentEvent event = toEvent(saved);
            String payload = objectMapper.writeValueAsString(event);

            OutboxEvent outboxEvent = new OutboxEvent();
            outboxEvent.setPaymentId(saved.getId());
            outboxEvent.setEventType("PaymentProcessed");
            outboxEvent.setPayload(payload);
            outboxEvent.setProcessed(false);
            outboxEvent.setCreatedAt(LocalDateTime.now());

            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to save outbox event: {}", e.getMessage());
        }

        return toResponse(saved);
    }

    public PaymentResponse findById(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
        return toResponse(payment);
    }

    public List<PaymentResponse> findAll() {
        return paymentRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private PaymentEvent toEvent(Payment payment) {
        return new PaymentEvent(
                payment.getId(),
                payment.getMerchant(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus().name()
        );
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getMerchant(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getCreatedAt()
        );
    }
}
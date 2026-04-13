package com.ciberaccion.paypro.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.ciberaccion.paypro.dto.CardValidationRequest;
import com.ciberaccion.paypro.dto.CardValidationResponse;
import com.ciberaccion.paypro.dto.DebitRequest;
import com.ciberaccion.paypro.dto.PaymentEvent;
import com.ciberaccion.paypro.dto.PaymentRequest;
import com.ciberaccion.paypro.dto.PaymentResponse;
import com.ciberaccion.paypro.exception.PaymentNotFoundException;
import com.ciberaccion.paypro.model.Payment;
import com.ciberaccion.paypro.model.PaymentStatus;
import com.ciberaccion.paypro.repository.PaymentRepository;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final AccountServiceClient accountServiceClient;
    private final ProviderServiceClient providerServiceClient;
    private final NotificationPublisher notificationPublisher;

    public PaymentService(PaymentRepository paymentRepository,
            AccountServiceClient accountServiceClient,
            ProviderServiceClient providerServiceClient,
            NotificationPublisher notificationPublisher) {
        this.paymentRepository = paymentRepository;
        this.accountServiceClient = accountServiceClient;
        this.providerServiceClient = providerServiceClient;
        this.notificationPublisher = notificationPublisher;
    }

    /*
     * public PaymentResponse create(PaymentRequest request) {
     * Payment payment = new Payment();
     * payment.setMerchant(request.getMerchant());
     * payment.setAmount(request.getAmount());
     * payment.setCurrency(request.getCurrency());
     * payment.setCreatedAt(LocalDateTime.now());
     * 
     * // 1. Validar tarjeta con provider
     * try {
     * CardValidationRequest cardRequest = new CardValidationRequest(
     * request.getCardNumber(),
     * request.getAmount(),
     * request.getCurrency());
     * 
     * Boolean approved = providerWebClient.post()
     * .uri("/provider/validate")
     * .bodyValue(cardRequest)
     * .retrieve()
     * .bodyToMono(com.ciberaccion.paypro.dto.CardValidationResponse.class)
     * .map(com.ciberaccion.paypro.dto.CardValidationResponse::isApproved)
     * .block();
     * 
     * if (approved == null || !approved) {
     * payment.setStatus(PaymentStatus.REJECTED);
     * return toResponse(paymentRepository.save(payment));
     * }
     * 
     * } catch (Exception e) {
     * payment.setStatus(PaymentStatus.REJECTED);
     * return toResponse(paymentRepository.save(payment));
     * }
     * 
     * // 2. Validar saldo con account-service
     * try {
     * accountWebClient.post()
     * .uri("/accounts/{merchantId}/debit", request.getMerchant())
     * .bodyValue(new DebitRequest(request.getAmount()))
     * .retrieve()
     * .toBodilessEntity()
     * .block();
     * 
     * payment.setStatus(PaymentStatus.APPROVED);
     * 
     * } catch (WebClientResponseException e) {
     * payment.setStatus(PaymentStatus.REJECTED);
     * }
     * 
     * Payment saved = paymentRepository.save(payment);
     * notificationPublisher.publish(toEvent(saved));
     * return toResponse(saved);
     * }
     */

    /*
     * public PaymentResponse create(PaymentRequest request) {
     * Payment payment = new Payment();
     * payment.setMerchant(request.getMerchant());
     * payment.setAmount(request.getAmount());
     * payment.setCurrency(request.getCurrency());
     * payment.setCreatedAt(LocalDateTime.now());
     * 
     * // 1. Validar tarjeta con provider
     * try {
     * boolean approved = validateCard(request);
     * if (!approved) {
     * payment.setStatus(PaymentStatus.REJECTED);
     * Payment saved = paymentRepository.save(payment);
     * notificationPublisher.publish(toEvent(saved));
     * return toResponse(saved);
     * }
     * } catch (Exception e) {
     * log.warn("Provider service unavailable: {}", e.getMessage());
     * payment.setStatus(PaymentStatus.REJECTED);
     * Payment saved = paymentRepository.save(payment);
     * notificationPublisher.publish(toEvent(saved));
     * return toResponse(saved);
     * }
     * 
     * // 2. Validar saldo con account-service
     * try {
     * debitAccount(request);
     * payment.setStatus(PaymentStatus.APPROVED);
     * } catch (Exception e) {
     * log.warn("Account service unavailable or insufficient funds: {}",
     * e.getMessage());
     * payment.setStatus(PaymentStatus.REJECTED);
     * }
     * 
     * Payment saved = paymentRepository.save(payment);
     * notificationPublisher.publish(toEvent(saved));
     * return toResponse(saved);
     * }
     */

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
                Payment saved = paymentRepository.save(payment);
                notificationPublisher.publish(toEvent(saved));
                return toResponse(saved);
            }
        } catch (Exception e) {
            log.warn("Provider validation failed: {}", e.getMessage());
            payment.setStatus(PaymentStatus.REJECTED);
            Payment saved = paymentRepository.save(payment);
            notificationPublisher.publish(toEvent(saved));
            return toResponse(saved);
        }

        // 2. Debitar cuenta
        try {
            accountServiceClient.debit(request);
            payment.setStatus(PaymentStatus.APPROVED);
        } catch (Exception e) {
            log.warn("Account debit failed: {}", e.getMessage());
            payment.setStatus(PaymentStatus.REJECTED);
        }

        Payment saved = paymentRepository.save(payment);
        notificationPublisher.publish(toEvent(saved));
        return toResponse(saved);
    }

    private PaymentEvent toEvent(Payment payment) {
        return new PaymentEvent(
                payment.getId(),
                payment.getMerchant(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus().name());
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

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getMerchant(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getCreatedAt());
    }

    // @CircuitBreaker(name = "providerService", fallbackMethod = "validateCardFallback")
    // @Retry(name = "providerService")
    // public boolean validateCard(PaymentRequest request) {
    //     CardValidationRequest cardRequest = new CardValidationRequest(
    //             request.getCardNumber(),
    //             request.getAmount(),
    //             request.getCurrency());
    //     CardValidationResponse response = providerWebClient.post()
    //             .uri("/provider/validate")
    //             .bodyValue(cardRequest)
    //             .retrieve()
    //             .bodyToMono(CardValidationResponse.class)
    //             .block();
    //     return response != null && response.isApproved();
    // }

    // public boolean validateCardFallback(PaymentRequest request, Exception e) {
    //     log.warn("Circuit breaker open for providerService: {}", e.getMessage());
    //     return false;
    // }

    // @CircuitBreaker(name = "accountService", fallbackMethod = "debitAccountFallback")
    // @Retry(name = "accountService")
    // public void debitAccount(PaymentRequest request) {
    //     accountWebClient.post()
    //             .uri("/accounts/{merchantId}/debit", request.getMerchant())
    //             .bodyValue(new DebitRequest(request.getAmount()))
    //             .retrieve()
    //             .toBodilessEntity()
    //             .block();
    // }

    // public void debitAccountFallback(PaymentRequest request, Exception e) {
    //     log.warn("Circuit breaker open for accountService: {}", e.getMessage());
    //     throw new RuntimeException("Account service unavailable");
    // }
}
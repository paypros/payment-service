package com.ciberaccion.paypro.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ciberaccion.paypro.model.Payment;
import com.ciberaccion.paypro.repository.PaymentRepository;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    // Crear un nuevo pago
    public Payment create(Payment payment) {
        payment.setStatus("PENDING"); // estado inicial
        payment.setCreatedAt(LocalDateTime.now());
        return paymentRepository.save(payment);
    }

    // Buscar pago por ID
    public Payment findById(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payment not found with id: " + id));
    }

    // Obtener todos los pagos
    public List<Payment> findAll() {
        return paymentRepository.findAll();
    }


}

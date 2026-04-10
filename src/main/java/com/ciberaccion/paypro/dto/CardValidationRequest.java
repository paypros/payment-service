package com.ciberaccion.paypro.dto;

import lombok.*;
import java.math.BigDecimal;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class CardValidationRequest {
    private String cardNumber;
    private BigDecimal amount;
    private String currency;
}
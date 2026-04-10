package com.ciberaccion.paypro.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class CardValidationResponse {
    private boolean approved;
    private String reason;
}
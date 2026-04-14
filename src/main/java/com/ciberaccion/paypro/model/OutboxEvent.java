// src/main/java/com/ciberaccion/paypro/model/OutboxEvent.java

package com.ciberaccion.paypro.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long paymentId;
    private String eventType;
    private String payload;
    private boolean processed;
    private LocalDateTime createdAt;
}
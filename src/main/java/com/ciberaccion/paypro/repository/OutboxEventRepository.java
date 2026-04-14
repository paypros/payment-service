// src/main/java/com/ciberaccion/paypro/repository/OutboxEventRepository.java

package com.ciberaccion.paypro.repository;

import com.ciberaccion.paypro.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByProcessedFalse();
}
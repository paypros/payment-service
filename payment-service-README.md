# payment-service

Microservicio principal del sistema PayPro. Orquesta el flujo completo de un pago coordinando validación de tarjeta, verificación de saldo y publicación de eventos.

## Stack
- Java 17 / Spring Boot 3.5.13
- PostgreSQL (en la misma Task de ECS para dev)
- WebClient (comunicación síncrona con otros servicios)
- AWS SQS (publicación de eventos asíncronos)
- AWS ECS Fargate / ECR Public
- GitHub Actions (CI/CD)
- Resilience4j (Circuit Breaker, Retry)

## Endpoints
```
POST /payments       → crear un pago
GET  /payments       → listar pagos
GET  /payments/{id}  → obtener pago por ID
```

## Flujo de un pago
```
POST /payments
    │
    ├── 1. provider-service → valida tarjeta (síncrono + Circuit Breaker)
    │         └── REJECTED si tarjeta bloqueada, red no soportada o monto > $10,000
    │
    ├── 2. account-service → valida y descuenta saldo (síncrono + Circuit Breaker)
    │         └── REJECTED si saldo insuficiente o servicio caído
    │
    ├── 3. Guarda pago con status APPROVED o REJECTED
    │
    └── 4. Publica evento PaymentProcessed en SQS (asíncrono)
```

## Variables de entorno (AWS)
```
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
SPRING_JPA_HIBERNATE_DDL_AUTO
PAYPRO_API_KEY
ACCOUNT_SERVICE_URL
PROVIDER_SERVICE_URL
SQS_QUEUE_URL
AWS_REGION
OTEL_TRACES_EXPORTER
```

## Autenticación
API Key via header `X-API-KEY`. Implementado como filtro `OncePerRequestFilter`. Los endpoints de Actuator están excluidos del filtro.

## Comunicación entre servicios
Usa AWS Cloud Map para DNS privado dentro de la VPC:
- `http://account-service.paypro.local:8081`
- `http://provider-service.paypro.local:8082`

Los servicios internos no tienen IP pública expuesta — solo `payment-service` es accesible desde internet.

## Outbox Pattern — Garantía de entrega de eventos

El problema sin Outbox:
```
paymentRepository.save(payment);       // 1. OK
notificationPublisher.publish(event);  // 2. falla → evento perdido
```
Estas dos operaciones no son atómicas — si SQS falla o la JVM crashea entre los dos pasos, el pago existe en DB pero el evento nunca se publica.

La solución:
```
@Transactional
saveWithOutbox():
  paymentRepository.save(payment)     // 1. INSERT payment
  outboxEventRepository.save(event)   // 2. INSERT outbox_event
  → commit atómico — ambos o ninguno

OutboxProcessor (@Scheduled cada 5s):
  3. Lee outbox_event WHERE processed=false
  4. Publica en SQS
  5. Marca processed=true
  → si SQS falla, el evento queda pendiente y se reintenta
```

El delay entre el pago y la notificación es de 3-10 segundos (tiempo del scheduler). No es inmediato pero sí **garantizado**.

## Resiliencia — Resilience4j
Configuración aplicada en `AccountServiceClient` y `ProviderServiceClient`:

```
Circuit Breaker:
  sliding-window-size: 5 llamadas
  failure-rate-threshold: 50% → abre el circuito
  wait-duration-in-open-state: 10s → espera antes de HALF_OPEN

Retry:
  max-attempts: 3
  wait-duration: 500ms entre intentos
  ignore: CallNotPermittedException (no reintenta cuando circuito está OPEN)
```

Estados del Circuit Breaker:
```
CLOSED    → operación normal, llamadas pasan
OPEN      → bloquea llamadas inmediatamente sin intentarlas
HALF_OPEN → deja pasar algunos requests para verificar recuperación
```

---

## Decisiones de diseño

### WebClient en lugar de RestTemplate
Se eligió `WebClient` sobre `RestTemplate` porque:
- `RestTemplate` está en modo mantenimiento desde Spring 5 — Spring ya no lo desarrolla activamente
- `WebClient` es el reemplazo oficial, funciona tanto síncrono como asíncrono
- Usamos `.block()` intencionalmente para mantener el código simple y síncrono
- La infraestructura ya está lista para evolucionar a reactivo si se necesita

En entrevistas: "Usamos WebClient síncrono por simplicidad, sabiendo que puede evolucionar a reactivo sin cambiar la arquitectura."

### Clientes separados para Resilience4j
`AccountServiceClient` y `ProviderServiceClient` son clases separadas de `PaymentService`. Esto es necesario porque Spring AOP no intercepta llamadas a métodos dentro de la misma clase — el proxy de AOP solo funciona cuando la llamada viene desde fuera de la clase.

---

## Retos encontrados y soluciones

### 1. Entidad JPA expuesta directamente en el Controller
**Problema:** El controller retornaba la entidad `Payment` directamente, acoplando la API al modelo de DB.
**Solución:** Se agregó `PaymentResponse` DTO. Si se agrega un campo interno a la tabla no aparece automáticamente en la respuesta.

### 2. `Double` para montos de dinero
**Problema:** `Double` tiene problemas de precisión con punto flotante — peligroso para cálculos financieros.
**Solución:** Cambiado a `BigDecimal` en el modelo, DTOs y lógica de negocio.

### 3. `status` como String
**Problema:** Strings mágicos como `"PENDING"` son propensos a typos y no se validan en compilación.
**Solución:** Creado enum `PaymentStatus` con `@Enumerated(EnumType.STRING)` para que Postgres guarde el texto en lugar del índice numérico.

### 4. `@ControllerAdvice` faltante
**Problema:** `GlobalExceptionHandler` existía pero Spring nunca lo registraba — los errores retornaban 500 genérico.
**Solución:** Agregada la anotación `@ControllerAdvice` a la clase.

### 5. `spring.profiles.active=dev` en producción
**Problema:** La propiedad `spring.profiles.active=dev` estaba activa en `application.properties`, haciendo que en AWS cargara H2 en lugar de Postgres.
**Solución:** Eliminado el perfil hardcodeado. Se usan valores default con el patrón `${VAR:default}` — en local usa H2 automáticamente, en AWS usa las variables de entorno de Postgres.

### 6. ECR privado → público
**Problema:** ECR privado tiene límite de 500MB/mes. Con múltiples servicios creciendo se alcanzaría fácilmente.
**Solución:** Migrado a ECR Public que ofrece 50GB/mes gratuitos.

### 7. IP dinámica de las Tasks en ECS
**Problema:** Cada vez que una Task de Fargate se reinicia obtiene una nueva IP, haciendo imposible hardcodear URLs entre servicios.
**Solución:** Implementado AWS Cloud Map para Service Discovery con DNS privado (`paypro.local`). Los servicios se llaman por nombre y Cloud Map actualiza el registro automáticamente.

### 8. `payment-service` sin permisos para SQS
**Problema:** Al deployar en ECS, el publisher fallaba silenciosamente porque no había credenciales de AWS para acceder a SQS.
**Solución:** Creado `ecsTaskRole` con política `AmazonSQSFullAccess` y asignado como `taskRoleArn` en la Task Definition. Diferencia clave: `executionRole` es para que ECS opere la infraestructura, `taskRole` es para que la aplicación acceda a servicios de AWS.

### 9. Task Definition con revisión anterior
**Problema:** Al registrar una nueva revisión de la Task Definition, el Service seguía usando la versión anterior.
**Solución:** Necesario ejecutar `aws ecs update-service --task-definition family:revision` explícitamente para que el Service use la nueva revisión.

### 10. Resilience4j no interceptaba llamadas internas
**Problema:** Las anotaciones `@CircuitBreaker` y `@Retry` en métodos de `PaymentService` no funcionaban porque Spring AOP no intercepta llamadas dentro de la misma clase.
**Solución:** Se extrajeron las llamadas a clases separadas `AccountServiceClient` y `ProviderServiceClient`. Al ser beans separados, Spring AOP puede interceptar las llamadas correctamente.

### 11. Retry reintentando con circuito OPEN
**Problema:** Cuando el circuito estaba OPEN, Retry seguía reintentando 3 veces generando logs confusos y demoras innecesarias.
**Solución:** Configurado `ignore-exceptions=CallNotPermittedException` en Retry para que no reintente cuando el circuito está abierto.

### 12. Jackson no serializaba `LocalDateTime`
**Problema:** `LocalDateTime` retornaba un array `[2026,4,13,21,57,0]` en lugar de string ISO.
**Solución:** Agregado `jackson-datatype-jsr310` al `pom.xml` y registrado `JavaTimeModule` en el `ObjectMapper`.

### 14. Duplicados en H2 al reiniciar account-service
**Problema:** Al reiniciar `account-service` varias veces en local con perfil `dev`, la tabla de cuentas acumulaba duplicados de `merchantId`. El debit fallaba con `Query did not return a unique result: 2 results were returned`.
**Causa:** H2 con `create-drop` limpia la DB al apagar, pero si el servicio se reinicia sin apagar limpio los datos persisten y se duplican al crear las cuentas de prueba nuevamente.
**Solución:** Reiniciar el servicio completamente desde el dashboard para forzar el `create-drop` y recrear las cuentas una sola vez.

### 13. Spring Boot 4.1.0-M1 con artefactos inexistentes
**Problema:** El proyecto fue generado con Spring Boot `4.1.0-M1` (versión milestone) con artefactos de nombres no estándar que no existen en Maven Central.
**Solución:** Migrado a Spring Boot `3.5.13` y corregidos los nombres de artefactos a los estándar: `spring-boot-starter-web`, `spring-boot-starter-test`, etc.

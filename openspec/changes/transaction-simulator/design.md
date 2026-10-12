# Design

## Context

Ver proposal.md - Why. El contrato del mensaje lo fija el notificator: `TransactionEvent` (`event_id`, `client_id`, `event_type`, `content`, `created_at`) deserializado desde un String JSON en snake_case, key = `client_id`. El manager ya publica en Kafka con el mismo patrón (`KafkaTemplate<String, String>` + `JsonMapper`, `acks=all`, idempotencia), que se reutiliza tal cual. El simulator no toca la DB.

## Goals / Non-Goals

**Goals:**
- 3000 eventos/min sostenidos con un código mínimo y fácil de leer.
- Un virtual thread por cliente, ritmo estable sin drift.
- Mensajes indistinguibles de los de la plataforma para el notificator.

**Non-Goals:**
- Arquitectura hexagonal: es una herramienta de desarrollo sin dominio propio; 3–4 clases planas alcanzan.
- Garantías exactas de tasa bajo fallas del broker (ver Risks).
- Endpoints HTTP, actuator o métricas propias.

## Decisions

### App Spring Boot sin web, módulo propio `transaction-simulator/`
Mismo stack y layout que manager/notificator (pom con parent Spring Boot 4.1.1, Java 25, `fmt-maven-plugin` check, `finalName app`, Dockerfile multi-stage). Dependencias: solo `spring-boot-starter-kafka` (+ Jackson que ya trae Spring Boot) y `spring-boot-starter-test`. `spring.main.web-application-type: none`.
- Alternativa: `main` Java puro con `kafka-clients`. Menos peso, pero rompe la convención del repo (config por properties, logging estructurado nativo, Dockerfile igual al resto). No compensa.

### Un virtual thread por cliente con pacing por deadline
Un `ApplicationRunner` arranca 8 virtual threads (`Thread.ofVirtual().name("sim-" + clientId)`). Cada uno publica cada `interval = 60 s × 8 / eventsPerMinute` (160 ms por defecto → 375/min por cliente, 3000/min total). El loop calcula el próximo deadline sumando `interval` al anterior (no a "ahora") y duerme hasta él, así el tiempo de generar/enviar no acumula drift. Si el thread quedó atrasado más de un intervalo (p. ej. broker caído y `send()` bloqueado hasta `max.block.ms`), el deadline se resetea a "ahora" para no publicar una ráfaga de recuperación.
- Alternativa: un `ScheduledExecutorService` con `scheduleAtFixedRate` cada 20 ms. Igual de corto, pero el pedido es explícito: virtual threads, y un thread por cliente refleja naturalmente "8 productores".
- Alternativa: `RateLimiter` de una librería. Dependencia nueva para lo que resuelven 5 líneas.

### Envío asíncrono
`kafkaTemplate.send(topic, clientId, json)` sin `.get()`; el resultado se observa con `whenComplete`: error → log ERROR con `event_id` y `client_id`. Así la latencia del ack no frena el ritmo. Con `acks=all` + idempotencia el productor ya reintenta internamente; `max.block.ms` corto (1 s) acota cuánto puede bloquear un thread cuando no hay metadata.

### Generación del evento
Función pura sobre un `RandomGenerator` inyectable (testeable con semilla):
- `event_id`: `"EVT-" + UUID.randomUUID()` (40 chars ≤ 64, único sin coordinación).
- `client_id`: el del thread (`CLIENT001`..`CLIENT008`, generados por índice a partir de `clients: 8`).
- `event_type`: al azar de los 10 tipos de `notification_events.json`, en una lista constante.
- `content`: texto sintético según el tipo, p. ej. `"Credit deposit of $123.45"`, monto aleatorio entre 1 y 5000.
- `created_at`: `Instant.now()`.
Record `SimulatedEvent` serializado con el `JsonMapper` de Spring (snake_case vía `spring.jackson.property-naming-strategy`).

### Configuración
`@ConfigurationProperties("simulator")` como record: `topic` (default `transactions.events.v1`), `events-per-minute` (default 3000, env `SIMULATOR_EVENTS_PER_MINUTE`), `clients` (default 8). Validación al arrancar: ambos > 0, si no, falla el arranque.

### docker-compose
Servicio `transaction-simulator` con `build: ./transaction-simulator`, `profiles: [simulator]`, `KAFKA_BOOTSTRAP_SERVERS: kafka:9092`, `depends_on: kafka-init: service_completed_successfully`. Sin puertos publicados.

### Logs
Logging estructurado JSON como el resto. INFO una vez al arrancar (tasa, clientes, intervalo). ERROR por envío fallido. DEBUG por envío exitoso (`event_id`, `client_id`, partición). Nunca `content`.

### Seguridad (OWASP)
Sin impacto en las superficies existentes. A05 Security Misconfiguration: el profile `simulator` evita que corra por accidente y no hay puertos expuestos; el broker local es PLAINTEXT sin auth, igual que hoy. Sin secretos ni datos reales: el `content` es sintético.

## Risks / Trade-offs

- [Clientes sin suscripción] → los eventos terminan en `NOT_SUBSCRIBED`; para ver entregas reales hay que insertar suscripciones a mano (fuera de alcance, documentar en README).
- [Tasa bajo falla del broker] → mientras `send()` bloquea, se publica menos de lo configurado y no se recupera el déficit (decisión explícita: evitar ráfagas).
- [Carga en DB] → 50 inserts/s + updates en el notificator; el profile permite apagarlo.
- [Precisión de `Thread.sleep`] → ±1–2 ms por tick sobre 160 ms; el pacing por deadline evita que se acumule.

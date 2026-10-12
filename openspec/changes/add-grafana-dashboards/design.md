# Design

## Context

- **Métricas actuales del notificator.** `notificator.deliveries{outcome,client_id}`, `notificator.discarded{reason}`, `notificator.replays{outcome}`, `notificator.dlt.messages{reason}`, `notificator.recovery.events{action}` y `notificator.webhook.latency{outcome}`. Esta última tiene `percentiles-histogram` activado en `application.yml`, con unos 70 buckets por serie. Todas las métricas se crean de forma perezosa con `meterRegistry.counter(...)` en el primer incremento.
- **Bug de conteo.** `StaleDeliveryRecoveryService` cierra eventos como `FAILED` (`failIfStale`) e incrementa solo `recovery.events{failed}`, no `deliveries{failed}`. `ExhaustedEventService` ignora en silencio el mensaje del DLT que ya no es el intento vigente.
- **Métricas que Spring Boot 4.1.1 ya registra.** `KafkaMetricsAutoConfiguration` agrega `MicrometerConsumerListener`/`MicrometerProducerListener` a las factories de Kafka, de donde salen `kafka_consumer_*` y `kafka_producer_*`. El tag `client.id` se exporta como label `client_id` (por ejemplo `consumer-notificator-retry-0-3`): coincide en nombre con el `client_id` del negocio, pero son cosas distintas. El listener publica `spring_kafka_listener_seconds{name,result,exception}`.
- **Retry topics.** Spring suma un sufijo al `groupId` de cada endpoint de retry y DLT, así que cada tópico tiene su propio consumer group: `notificator`, `notificator-retry-0`… y `notificator-dlt`. Los nombres exactos se confirman contra el broker.
- **Fallos transitorios de la base.** Se reintentan en el mismo offset, con reintentos bloqueantes configurados en `KafkaRetryConfig.configureBlockingRetries`, y solo para `TransientDataAccessException`, `DataAccessResourceFailureException` y `RecoverableDataAccessException`.
- **Fechas.** `notification_event.created_at` guarda el `created_at` del mensaje original, y `NotificationEvent.toTransactionEvent()` lo conserva. Por eso un retry, que lee la fila, tiene el mismo `created_at` que el intento 1.
- **Fallos de transporte.** `WebhookResult.TransportError.reason` trae el nombre simple de la causa: `HttpTimeoutException`, `HttpConnectTimeoutException`, `ConnectException`, etc.
- **Alloy.** Ya corre (v1.10.0) para los logs. Su servidor HTTP escucha en el default, `127.0.0.1:12345`.
- **Webhook mock.** El mock (`webhook-mock`) se configura por cliente (status y delay). El seed suscribe a `CLIENT001`..`CLIENT008` a todos los tipos de evento.

## Goals / Non-Goals

**Goals:**
- Cada panel de los specs sale de una consulta PromQL o LogQL sobre una métrica que el código emite. No hay paneles "a futuro".
- La cardinalidad por cliente es acotada y predecible.
- Cero contenedores nuevos y cero dependencias nuevas.

**Non-Goals:**
- Medir la latencia de punta a punta de los replays. Su `created_at` es el original y mezclaría horas o días con segundos.
- Contar los errores de base del job de recuperación como errores de persistencia: no pasan por Kafka. Quedan en el log ERROR `Recovery publish failed` y en las métricas de Hikari.

## Decisions

### 1. Un contador por intento al webhook, medido en el cliente HTTP

`notificator.webhook.attempts{client_id, attempt, status, outcome}` se incrementa en `RestClientWebhookClient.post`. Es el único punto por donde pasa cada POST y ya tiene `event.clientId()`, `attempt` y el `WebhookResult`.

- `status`: el código HTTP como string (`"200"`, `"404"`, `"503"`) o, para `TransportError`, un valor acotado:
  - `timeout` si la causa termina en `TimeoutException` (incluye `HttpConnectTimeoutException`);
  - `connect_error` para `ConnectException`, `ClosedChannelException` y `UnresolvedAddressException`. Con el `HttpClient` del JDK, la causa raíz de un puerto cerrado es `ClosedChannelException` y la de un host que no resuelve es `UnresolvedAddressException`, ambas envueltas en `ConnectException`;
  - `io_error` para cualquier otra.

  Nunca se usa el nombre crudo de la excepción, para no abrir cardinalidad.
- `outcome`: `DeliveryOutcome.of(result)` en minúsculas (`delivered|retryable|permanent`). Es redundante con `status`, pero no suma series (depende funcionalmente de él) y simplifica las consultas.
- `attempt`: va de 1 a 4. Cuando el intento termina en "URL no permitida" no hay POST y no se cuenta, que es lo correcto: no es un error del webhook.
- Alternativa descartada: contar en `DeliveryService` después de `webhook.post`. Ahí el número de intento es el mismo, pero dejaría la latencia y el contador en clases distintas que miden la misma llamada.
- Alternativa descartada: usar `notificator_webhook_latency_seconds_count` como contador de intentos. Haría falta agregarle `attempt` y `status` al timer, y con histograma eso multiplica los buckets.

### 2. Latencia del webhook por cliente con buckets SLO

Se le agrega el tag `client_id` a `notificator.webhook.latency`. En `application.yml` se reemplaza `percentiles-histogram` por `slo`:

```yaml
management.metrics.distribution:
  slo:
    notificator.webhook.latency: 5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s,2500ms,5s,10s
    notificator.delivery.e2e: 100ms,500ms,1s,5s,10s,30s,1m,2m,5m,10m,30m,1h,2h
```

- Con `percentiles-histogram` hay unos 70 buckets por combinación (`client_id` × `outcome`). Con SLO quedan 12 (11 más `+Inf`). Por cliente pasan de unas 210 series a 36.
- El último bucket es 10 s porque es el `read-timeout` por defecto: más allá de ese valor todo es `+Inf`, que es la información correcta.
- Alternativa descartada: dejar el histograma sin `client_id` y mostrar la latencia del cliente como promedio (`sum/count`). El panel pide la latencia de su webhook, y un promedio esconde la cola, que es justo lo que importa ver por cliente.

### 3. Latencia de punta a punta en `DeliveryService`

Es un timer `notificator.delivery.e2e`, sin tags, que se registra justo después de que `markDelivered` devuelve `true` y solo cuando `generation == 0`. El valor es `Duration.between(event.createdAt(), clock.instant())`.

- Sin `client_id`: el dashboard por cliente no lo pide y así se evita multiplicar 14 buckets por cliente.
- Los buckets llegan a 2 h porque el peor caso entregado es 60 + 300 + 1500 s de reintentos más el lease y la demora del job de recuperación, alrededor de 31 a 35 min.
- **Clock skew.** `created_at` lo pone el productor. Si su reloj adelanta, la duración puede dar negativa: se registra `max(0, d)`.

### 4. Correcciones de conteo y pre-registro

- `StaleDeliveryRecoveryService`: cuando `failIfStale` gana, también incrementa `notificator.deliveries{outcome="failed", client_id}`.
- `ExhaustedEventService`: cuando `recordAttempt` no afecta filas, incrementa `notificator.discarded{reason="dlt_ignored"}`.
- **Pre-registro.** Cada clase registra en su constructor los contadores de valores conocidos, con `meterRegistry.counter(name, tag, value)` sin incrementarlos:
  - `TransactionEventListener`: `dlt.messages` × `exhausted|invalid|unexpected`.
  - `StaleDeliveryRecoveryService`: `recovery.events` × `republished|failed`.
  - `DeliveryService`: `discarded` × `duplicate|stale|dlt_ignored` y `replays` × `started|discarded`.

  Sin esto, `increase()` ignora la primera muestra de una serie nueva y el panel "1 evento al DLT" muestra 0.
  - No se pre-registran `deliveries` ni `webhook.attempts`: dependen de `client_id`, que no se conoce de antemano. Con el volumen del simulador la pérdida es de un evento por serie nueva.

### 5. Errores de persistencia con un `RetryListener` del error handler

En `KafkaRetryConfig` se sobrescribe `configureCustomizers` para agregar al `DefaultErrorHandler` un `RetryListener` que en `failedDelivery` incrementa `notificator.persistence.errors{exception}`. Cuenta solo cuando la causa (recorriendo la cadena) es una de las tres excepciones de la base que se reintentan en el mismo offset. `exception` es el nombre simple de esa causa, que tiene pocos valores posibles.

- Es un único punto de enganche que cubre los tres tópicos principales, retry y DLT, y no toca los adapters JDBC.
- Se filtra por tipo porque el mismo error handler también ve `RetryableDeliveryException`, que manda al siguiente tópico de retry y es un error del webhook, no de la base.
- Alternativa descartada: `spring_kafka_listener_seconds_count{result="failure"}` filtrado por `exception`. No necesita código, pero el tag guarda la excepción que ve el listener (a veces envuelta) y la consulta dependería de una regex sobre nombres de clase.
- Alternativa descartada: contar en cada método de `JdbcNotificationEventRepository`. Son unos 10 puntos, contra uno solo.

### 6. Lag y offsets de Kafka: el exporter embebido de Alloy

```
 kafka:9092 <--Admin API-- Alloy: prometheus.exporter.kafka "default"
                               |  /api/v0/component/prometheus.exporter.kafka.default/metrics
                               v
                 Prometheus job "kafka" (alloy:12345)  --> Grafana
```

- **En `config.alloy`:** `prometheus.exporter.kafka "default" { kafka_uris = ["kafka:9092"] }`. Por dentro es kafka_exporter, pero la versión de Alloy publica el lag como `kafka_consumergroup_uncommitted_offsets{consumergroup,topic,partition}` (no `kafka_consumergroup_lag`), junto con `kafka_topic_partition_current_offset{topic,partition}`. Un grupo solo tiene series de lag para los tópicos donde ya comiteó algún offset: un grupo de retry sin mensajes figura únicamente en `kafka_consumergroup_members`. Las particiones de esos tópicos donde el grupo todavía no comiteó valen `-1`; los paneles las filtran con `>= 0`. Verificado contra Alloy v1.10.0.
- **En compose:** Alloy suma `--server.http.listen-addr=0.0.0.0:12345` a su `command`, sin publicar el puerto.
- **En Prometheus:** un job `kafka` con `metrics_path` en el endpoint del componente y el label `app: kafka`.
- Alternativa descartada: un contenedor `danielqsj/kafka-exporter`. Es el mismo código con un servicio más que operar.
- Alternativa descartada: KMinion. Agrega sondas y estado de grupos que ningún panel pide.
- Alternativa descartada: Burrow, pesado y con poco mantenimiento.
- Alternativa descartada: kafka-lag-exporter, que está archivado.
- Alternativa descartada: JMX exporter en el broker. El broker no expone el lag por grupo. Sí expone `MessagesInPerSec` por tópico, pero el offset ya da lo mismo.
- Alternativa descartada: `remote_write` de Alloy a Prometheus. Necesita `--web.enable-remote-write-receiver` en Prometheus y Prometheus pierde el control del scrape, sin ganar nada.
- **Mensajes por minuto:** salen del `rate` del offset. Los tópicos son `cleanup.policy=delete` y el producer es idempotente pero no transaccional, así que no hay markers de control: un offset equivale a un mensaje.

### 7. Rebalanceos desde las métricas del cliente

Se usa `kafka_consumer_coordinator_last_rebalance_seconds_ago{app="notificator"}`, un gauge que cae a casi 0 en cada rebalanceo. `resets()` cuenta esas caídas. El grupo se saca del label `client_id` del cliente Kafka, y se toma `max` por grupo porque todos los miembros ven el mismo rebalanceo:

```
max by (group) (label_replace(resets(kafka_consumer_coordinator_last_rebalance_seconds_ago{app="notificator"}[5m]),
  "group", "$1", "client_id", "consumer-(.+)-[0-9]+"))
```

- **Formato de `client_id`, verificado:** `consumer-<group>-<n>` (por ejemplo `consumer-notificator-retry-0-10`). Es el `client.id` por defecto de Kafka.
- **Descartado: `increase(kafka_consumer_coordinator_rebalance_total)`.** La secuencia `<n>` se reinicia con cada JVM, así que después de un reinicio los consumidores tienen las mismas etiquetas que antes y su contador vuelve a 1. Prometheus ve 1 → 1, sin reset, y el rebalanceo del reinicio queda en 0. Tampoco sirve `sum` por grupo, que cuenta una vez por miembro.
- **Lo que se ve al reiniciar:** dos rebalanceos por grupo, uno cuando los consumidores salen al detenerse (`LeaveGroup`) y otro cuando entran al arrancar.
- El broker (incluso por JMX) solo tiene contadores de rebalanceo agregados de todos los grupos.

### 8. Dashboards

Son 4 archivos en `observability/grafana/dashboards/`: `general.json`, `notificator.json`, `cliente.json` y `kafka.json`, con uid igual al nombre del archivo. Se borra `apps.json`.

Opciones comunes a los cuatro: tag `cobre`, `editable: false`, `refresh: 30s`, `now-1h` y datasources por uid (`prometheus`, `loki`).

Consultas principales (`D = notificator_deliveries_total`, `A = notificator_webhook_attempts_total`):

| Dashboard | Panel | Consulta |
|---|---|---|
| General | Tasa de éxito (stat) | `sum(increase(D{outcome="delivered"}[$__range])) / sum(increase(D{outcome=~"delivered\|failed"}[$__range]))` |
| General | Entregas/min | `sum(rate(D{outcome="delivered"}[2m])) * 60` |
| General | DLT última hora (stat) | `sum(increase(notificator_dlt_messages_total[1h]))` |
| General | p95 webhook y punta a punta | `histogram_quantile(0.95, sum by (le)(rate(notificator_webhook_latency_seconds_bucket[5m])))` y lo mismo con `notificator_delivery_e2e_seconds_bucket` |
| General | Lag notificator (stat) | `sum(kafka_consumergroup_uncommitted_offsets{consumergroup="notificator"} >= 0)` |
| General | Recuperados | `sum by (action)(increase(notificator_recovery_events_total[$__range]))` |
| General / Plataforma | Targets, HTTP por status, replays del manager | Las consultas actuales de `apps.json`, sin cambios |
| Notificator | Intentos por resultado | `sum by (outcome)(rate(A[2m])) * 60` |
| Notificator | Por número de intento | `sum by (attempt, outcome)(increase(A[$__range]))` (barras apiladas) |
| Notificator | Estados finales | `sum by (outcome)(increase(D{outcome=~"delivered\|failed\|not_subscribed"}[$__range]))` |
| Notificator | Tasa de NOT_SUBSCRIBED | `not_subscribed / (delivered + failed + not_subscribed)` en `$__range` |
| Notificator | 0 filas | `sum by (reason)(increase(notificator_discarded_total[5m]))` y `sum(increase(notificator_replays_total{outcome="discarded"}[5m]))` como serie `replay` |
| Notificator | Persistencia vs webhook | `sum(rate(notificator_persistence_errors_total[2m]))*60` vs `sum(rate(A{outcome!="delivered"}[2m]))*60` |
| Cliente | Éxito, volumen, p95 | Las mismas consultas con `{client_id="$client"}` |
| Cliente | Fallos por código | `sum by (status)(increase(A{client_id="$client", outcome!="delivered"}[5m]))` |
| Cliente | Logs | Ver abajo |
| Kafka | Lag | `sum by (consumergroup, topic, partition)(kafka_consumergroup_uncommitted_offsets{consumergroup=~"notificator.*"} >= 0)` |
| Kafka | Msgs/min retry y DLT | `sum by (topic)(rate(kafka_topic_partition_current_offset{topic=~"notificator\\.(retry\\.v1-.*\|dlt\\.v1)"}[2m])) * 60` |
| Kafka | Rebalanceos | Decisión 7 (`resets` sobre `last_rebalance_seconds_ago`) |

- **Variable `client`:** se lee de `label_values(notificator_deliveries_total, client_id)`, con selección única y sin opción "All". Nunca se lee de una métrica `kafka_*`, porque ahí `client_id` es el id del consumidor Kafka.
- **Panel de logs del cliente:**
  ```
  {app="notificator"} | json | client_id="$client"
    | message=~"Delivery attempt failed.*|Event failed.*|Message in DLT|Stale last attempt failed"
    | line_format "{{.event_id}} attempt={{.attempt}} {{.error}}"
  ```
  Se aplica sobre el JSON, no sobre la línea formateada, para que un `error` con texto libre no se cuele en el filtro de otro cliente.
- **Lag del dashboard General:** muestra solo el grupo `notificator` (tópico principal y replays). En los grupos de retry, el lag incluye mensajes que esperan su backoff a propósito: se ven en `Kafka`, separados por grupo, y no en el número de salud.

## Risks / Trade-offs

- **[Cardinalidad por `client_id`]** Por cliente son unas 36 series de latencia más las combinaciones de `attempts` que realmente se den (la mayoría de los clientes solo tienen `200`/`attempt=1`). Con miles de clientes son decenas de miles de series → ya está aceptado para el caso. Si crece, se pasa a un recording rule por cliente o se saca `attempt` del contador con `client_id`.
- **[Lag de los grupos de retry]** Su lag sube por diseño mientras esperan el backoff y podría leerse como un problema → el panel General excluye esos grupos y el de Kafka los separa.
- **[Alloy escuchando en `0.0.0.0` dentro de la red de compose (OWASP A05 Security Misconfiguration)]** Su UI y su API no tienen autenticación y muestran los targets de Docker que descubre → el puerto no se publica en el host, igual que los actuators. En producción, el lag se recolecta con el agente de la plataforma.
- **[Datos sensibles en métricas (OWASP A09 Security Logging and Monitoring Failures / A02)]** → los tags son solo `client_id`, `attempt`, `status`, `outcome`, `reason` y `exception` (nombre de clase). Nunca `content`, la URL del webhook ni mensajes de excepción, que pueden repetir datos del request. Un test lo verifica en el scrape.
- **[`$client` interpolado en LogQL]** Los `client_id` que vienen de los eventos tienen hasta 64 caracteres pero no un charset restringido. Un valor con comillas puede romper la consulta del panel → el peor caso es un error de consulta en una herramienta interna, sin escribir nada. No justifica escapar.
- **[El path del componente de Alloy o el formato de `client.id` no son los esperados]** → las primeras tareas los verifican contra el stack levantado antes de escribir los dashboards. Plan B para Alloy: `remote_write`.
- **[Se pierde el primer incremento de series por cliente]** → aceptado, y documentado en la decisión 4.
- **[Links a `Apps`]** → se rompen. El README apunta a `General`.

## Migration Plan

1. Archivar `add-observability-stack` y `add-logs-dashboard`: los deltas de `observability-stack` y `logs-dashboard` modifican requisitos que esos changes agregan.
2. Reconstruir el notificator (`docker compose up -d --build notificator`) y recrear Alloy, Prometheus y Grafana (`docker compose up -d alloy prometheus grafana`). El provider de Grafana deja de cargar `apps.json` y carga los nuevos archivos.
3. Rollback: revertir el commit y repetir el paso 2. Al cambiar los buckets del timer de latencia, las series viejas `_bucket` quedan en Prometheus hasta que vence su retención. No molestan porque las consultas nuevas agrupan por `le`, pero un p95 que cruce el cambio de buckets muestra un salto en ese momento.

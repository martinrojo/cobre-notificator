# Tasks

## 1. Métricas del notificator: correcciones y pre-registro

- [x] 1.1 `StaleDeliveryRecoveryService`: cuando `failIfStale` gana, incrementar también `notificator.deliveries{outcome="failed", client_id}`; extender `StaleDeliveryRecoveryServiceTest` (SimpleMeterRegistry) verificando ambos contadores en 1 y que un `failIfStale` perdido no incrementa ninguno; `mvn test` en `notificator`
- [x] 1.2 `ExhaustedEventService`: cuando `recordAttempt` devuelve `false`, incrementar `notificator.discarded{reason="dlt_ignored"}` (constante `DeliveryService.DISCARDED`); test en `ExhaustedEventServiceTest` del caso ignorado (contador en 1, `deliveries{failed}` sin cambios); `mvn test`
- [x] 1.3 Pre-registrar en el constructor los contadores de valores conocidos (`dlt.messages` × `exhausted|invalid|unexpected` en `TransactionEventListener`, `recovery.events` × `republished|failed` en `StaleDeliveryRecoveryService`, `discarded` × `duplicate|stale|dlt_ignored` y `replays` × `started|discarded` en `DeliveryService`); un test por clase que construye el servicio con un `SimpleMeterRegistry` y verifica que cada serie existe con valor 0; `mvn test`

## 2. Métricas del notificator: intentos y latencias

- [x] 2.1 `RestClientWebhookClient.post`: incrementar `notificator.webhook.attempts{client_id, attempt, status, outcome}` y agregar `client_id` al timer `notificator.webhook.latency`. `status` es el código HTTP como string, o `timeout`, `connect_error` o `io_error` para `TransportError` (mapeo de la decisión 1 del design). En `RestClientWebhookClientTest`: casos 200, 404 y 503 contra el servidor de test, timeout con read-timeout corto, conexión rechazada a un puerto cerrado, y verificación de que ningún tag contiene la URL ni el body; `mvn test`
- [x] 2.2 `DeliveryService`: registrar `notificator.delivery.e2e` con `max(0, now - createdAt)` solo cuando `markDelivered` devuelve `true` y `generation == 0`. Tests en `DeliveryServiceTest` con `Clock` fijo: entrega en generación 0 (1 observación del valor esperado), replay entregado en generación 1 (0 observaciones), `markDelivered` perdido (0 observaciones), `createdAt` en el futuro (observación 0); `mvn test`
- [x] 2.3 `application.yml`: reemplazar `percentiles-histogram` por `slo` para `notificator.webhook.latency` (5ms..10s) y `notificator.delivery.e2e` (100ms..2h) según el design; verificar con un test de contexto (`NotificatorApplicationTest` o uno nuevo) que `notificator_webhook_latency_seconds_bucket` expone los `le` configurados y no los ~70 por defecto; `mvn test`

## 3. Métricas del notificator: errores de persistencia

- [x] 3.1 `KafkaRetryConfig`: sobrescribir `configureCustomizers` y agregar al `DefaultErrorHandler` un `RetryListener` que en `failedDelivery` incremente `notificator.persistence.errors{exception}` solo si la cadena de causas contiene `TransientDataAccessException`, `DataAccessResourceFailureException` o `RecoverableDataAccessException` (tag = nombre simple de esa causa). Test unitario del listener: un `QueryTimeoutException` envuelto en `ListenerExecutionFailedException` cuenta; un `RetryableDeliveryException` y un `DataIntegrityViolationException` no; `mvn test`
- [x] 3.2 README, tabla de métricas del notificator: agregar `notificator_webhook_attempts_total`, `notificator_delivery_e2e_seconds` y `notificator_persistence_errors_total` con sus tags, `client_id` en la latencia del webhook y `dlt_ignored` en `notificator_discarded_total`; aclarar que `failed` incluye los cierres del job de recuperación; verificar contra `/actuator/prometheus` del notificator levantado (`docker compose exec notificator wget -qO- localhost:9391/actuator/prometheus | grep notificator_`) que cada nombre y tag documentado existe

## 4. Lag y offsets de Kafka vía Alloy

- [x] 4.1 `observability/alloy/config.alloy`: agregar `prometheus.exporter.kafka "default"` con `kafka_uris = ["kafka:9092"]`; en `docker-compose.yml`, sumar `--server.http.listen-addr=0.0.0.0:12345` al `command` de Alloy sin publicar el puerto; verificar con `docker compose up -d alloy` y `docker compose exec prometheus wget -qO- http://alloy:12345/api/v0/component/prometheus.exporter.kafka.default/metrics | grep kafka_consumergroup_uncommitted_offsets` (si el path difiere, usar el que liste la UI de Alloy y actualizar el design)
- [x] 4.2 `observability/prometheus/prometheus.yml`: job `kafka` con target `alloy:12345`, el `metrics_path` verificado en 4.1 y el label `app: kafka`; verificar en Prometheus que `up{app="kafka"}` vale 1, que `kafka_topic_partition_current_offset` tiene series para `notificator.retry.v1-0..2` y `notificator.dlt.v1`, y que con el notificator detenido y el simulador publicando, `sum(kafka_consumergroup_uncommitted_offsets{consumergroup="notificator"})` crece separado por particiones 0, 1 y 2; anotar los nombres reales de los consumer groups de retry/DLT y confirmar que `docker compose ps` no muestra puertos nuevos
- [x] 4.3 Confirmar el formato del label `client_id` en `kafka_consumer_coordinator_rebalance_total{app="notificator"}` (esperado `consumer-<group>-<n>`) y que la regex de la decisión 7 extrae los mismos grupos que 4.2, reiniciando el notificator y viendo el `increase`; si difiere, ajustar la regex en el design
- [x] 4.4 README, sección de observabilidad: mencionar que Alloy también publica el lag y los offsets de Kafka, scrapeados por Prometheus como `app=kafka`, sin puertos publicados; verificar que la consulta de ejemplo documentada devuelve datos

## 5. Dashboards

- [x] 5.1 `observability/grafana/dashboards/general.json` (uid `general`) con los paneles de la tabla del design y la fila colapsada "Plataforma" con las consultas de targets, HTTP por status y replays de `apps.json`; verificar con `python3 -m json.tool` y que cada consulta devuelve datos vía `/api/ds/query` con el simulador corriendo
- [x] 5.2 `notificator.json` (uid `notificator`) con los paneles del design; verificar JSON válido y, con el mock configurado para que `CLIENT002` responda 503, que el panel por número de intento muestra intentos 1..4 retryable y los estados finales suman `FAILED`
- [x] 5.3 `cliente.json` (uid `cliente`) con la variable `client` (`label_values(notificator_deliveries_total, client_id)`, selección única, sin All) y los paneles filtrados por `client_id="$client"`, incluido el panel de logs de Loki del design; verificar JSON válido y, con el mock (`CLIENT002` → 503, `CLIENT003` → delay 15000, `CLIENT004` → 404), que el panel de fallos muestra `503`, `timeout` y `404` respectivamente y que con `CLIENT001` ningún panel muestra datos de otro cliente (incluido el de logs)
- [x] 5.4 `kafka.json` (uid `kafka`) con lag por grupo/tópico/partición, mensajes/min en retry y DLT y rebalanceos por grupo; verificar JSON válido y que con `CLIENT002` en 503 aparecen mensajes en `notificator.retry.v1-0`, `-1`, `-2` y `notificator.dlt.v1`
- [x] 5.5 Borrar `apps.json`; con el volumen de Grafana vacío (`docker compose rm -sfv grafana && docker volume rm <proyecto>_grafana-data && docker compose up -d grafana`), `curl -u admin:$GRAFANA_ADMIN_PASSWORD 'localhost:3000/api/search?tag=cobre'` devuelve `general`, `notificator`, `cliente`, `kafka` y `logs`, y no `apps`
- [x] 5.6 README, sección de observabilidad: reemplazar `Apps` por los cuatro dashboards con una línea por cada uno, y un ejemplo de cómo usar el mock para ver fallos en `Cliente`; verificar que los comandos del ejemplo producen los paneles descritos

## 6. Verificación integral

- [x] 6.1 Recorrer los escenarios de `specs/operations-dashboards/spec.md` con el stack, el simulador y el mock: tasa de éxito (incluido un `FAILED` del job de recuperación, forzado con `DELIVERY_LEASE` corto y el notificator detenido a mitad de reintentos), DLT contado desde el primer mensaje tras reiniciar el notificator, latencia de punta a punta mayor o igual a 60 s con un 503 seguido de 200, errores de persistencia con MariaDB detenida, y un `NOT_SUBSCRIBED` publicando un evento para un cliente sin suscripción; `mvn test` en `notificator` en verde

## Workflow follow-up

- Archivar `add-observability-stack` y `add-logs-dashboard` antes que este change: los deltas MODIFIED de `observability-stack` y `logs-dashboard` necesitan que sus specs ya existan en `openspec/specs/`.

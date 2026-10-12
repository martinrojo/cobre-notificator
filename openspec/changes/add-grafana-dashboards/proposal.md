# Proposal

## Why

El dashboard `Apps` muestra métricas sueltas. No responde las preguntas de operación: qué porcentaje de entregas sale bien, cuánto tarda un evento en llegar al cliente, a qué intento se entrega, qué le pasa al webhook de un cliente puntual o cuánto atraso tiene Kafka. Varias de esas respuestas ni siquiera existen como métrica: no hay tag de número de intento, ni código HTTP, ni latencia por cliente, ni latencia punta a punta, ni lag de los consumer groups. Además hay un bug: el job de recuperación cierra eventos como `FAILED` sin contarlos en las entregas, así que la tasa de éxito sale inflada justo durante los incidentes.

## What Changes

- **Cuatro dashboards nuevos** provisionados como código: `General`, `Notificator`, `Cliente` (variable `$client`) y `Kafka`.
  - **General**: tasa de éxito, entregas por minuto, eventos al DLT en la última hora, p95 del webhook y de punta a punta, lag del notificator, eventos recuperados. Incluye una fila colapsada "Plataforma" con targets, HTTP por status y replays del manager.
  - **Notificator**: intentos por resultado, distribución por número de intento, estados finales (`DELIVERED`/`FAILED`/`NOT_SUBSCRIBED`), tasa de `NOT_SUBSCRIBED`, actualizaciones con 0 filas, errores de persistencia contra errores del webhook.
  - **Cliente**: tasa de éxito y volumen, fallos por código HTTP (incluido timeout), latencia de su webhook y logs de Loki con sus intentos fallidos y el `event_id`.
  - **Kafka**: lag por consumer group y partición, mensajes por minuto en cada tópico de retry y en el DLT, rebalanceos por consumer group.
- **Se elimina** el dashboard `Apps` (`apps.json`). Sus paneles pasan a `General` y `Notificator`. **BREAKING** para quien tenga links a `Apps`.
- **Métricas nuevas del notificator**:
  - Contador de intentos al webhook por `client_id`, número de intento, código HTTP o tipo de error de transporte (`timeout`, `connect_error`) y resultado.
  - Latencia de punta a punta: de `created_at` del evento hasta `DELIVERED`.
  - Errores de persistencia: fallos de la base reintentados en el mismo offset.
- **Cambios en métricas existentes**:
  - La latencia del webhook suma el tag `client_id` y pasa a usar buckets fijos acotados.
  - Un evento cerrado como `FAILED` por el job de recuperación ahora cuenta en `notificator_deliveries_total{outcome="failed"}` (fix).
  - Un mensaje del DLT que ya no corresponde al intento vigente cuenta como descarte (`reason="dlt_ignored"`).
  - Los contadores con tags de valores conocidos se registran en 0 al arrancar, para que `increase()` no pierda el primer evento.
- **Lag de Kafka del lado del broker**: Alloy, que ya está en el stack, usa su exporter embebido (`prometheus.exporter.kafka`) para publicar el lag por consumer group y partición y los offsets por tópico. Prometheus lo scrapea. No se agrega ningún contenedor.

Aplicaciones tocadas: **notificator** (métricas). **manager** y **gateway** no cambian. También cambia la configuración de observabilidad: Alloy, Prometheus y los dashboards de Grafana.

### Fuera de alcance

- Alertas (Alertmanager o alerting de Grafana) sobre estas métricas.
- Métricas del broker por JMX (ISR, discos, requests): ningún panel pedido las necesita.
- Paneles del gateway más allá del target `up` que ya existe.
- Circuit breaker: todavía no está implementado. Cuando exista, su estado y el tag `circuit_open` van en su propio change.
- Un panel de estados actuales leídos de la base (por ejemplo, cuántos eventos hay en `RETRYING` ahora). Los dashboards muestran flujos (contadores), no inventario.
- Control de acceso por cliente en Grafana: es una herramienta interna de operación.
- Cambios en el mock de webhooks o en el simulador.

## Capabilities

### New Capabilities

- `operations-dashboards`: dashboards de operación en Grafana (General, Notificator, Cliente y Kafka) con sus paneles y la variable de cliente.

### Modified Capabilities

- `notification-delivery`: el requisito "Observabilidad de la entrega" suma intentos por intento y código, latencia por cliente, latencia de punta a punta, errores de persistencia y descartes del DLT. También corrige el conteo de `FAILED` del job de recuperación.
- `observability-stack`: suma la recolección de métricas de consumer groups y tópicos de Kafka. El provisioning de Grafana carga los dashboards nuevos en lugar de `Apps`.
- `logs-dashboard`: el escenario de arranque deja de mencionar `Apps`.

## Impact

- **Código del notificator**: `RestClientWebhookClient`, `DeliveryService`, `StaleDeliveryRecoveryService`, `ExhaustedEventService`, `KafkaRetryConfig` y `application.yml`, más sus tests.
- **Observabilidad**: `observability/alloy/config.alloy`, `observability/prometheus/prometheus.yml`, `docker-compose.yml` (dirección de escucha de Alloy), `observability/grafana/dashboards/` (4 archivos nuevos, se borra `apps.json`).
- **README**: tabla de métricas del notificator y sección de observabilidad.
- **Cardinalidad**: `client_id` como tag en métricas de intentos y de latencia. Ya está aceptado para el volumen de clientes del caso.
- **Dependencias nuevas**: ninguna.
- **Orden de archivado**: depende de `add-observability-stack` y `add-logs-dashboard`, que deben archivarse antes que este change. Los deltas de `observability-stack` y `logs-dashboard` modifican requisitos que esos changes agregan.

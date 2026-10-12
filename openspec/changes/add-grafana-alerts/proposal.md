# Proposal

## Why

Los dashboards muestran el estado del sistema, pero alguien tiene que estar mirándolos. Si la base se cae, el notificator deja de consumir o un bug nuestro hace que todos los webhooks rechacen la entrega, no hay nada que lo marque. El caso pide observabilidad casi en tiempo real, y eso incluye enterarse de un problema sin tener que buscarlo.

## What Changes

- **Reglas de alerta provisionadas como código** en el alerting de Grafana, cargadas desde el repo y sin edición desde la UI, igual que los dashboards.
- **Tres severidades** como label (`critical`, `warning`, `info`). El criterio es de quién es la falla: nuestra (critical/warning) o del webhook de un cliente (info).
  - **critical**: app caída, el notificator no consume `transactions.events.v1`, errores de persistencia, mensajes al DLT por motivo `invalid` o `unexpected`, replay del manager sin poder publicar en Kafka.
  - **warning**: más del 30% de los clientes activos con la mayoría de sus intentos fallando, 5xx del manager (sin el 503 del replay), eventos cerrados como `FAILED` por el job de recuperación.
  - **info**: circuito de un cliente abierto por más de 5 minutos, una alerta por cliente.
- **Panel de alertas activas** en el dashboard `General`.
- **README**: tabla de alertas y cómo dispararlas en local con el mock de webhooks.
- **Manager**:
  - Los contadores de `manager_replay_requests_total` se registran en 0 al arrancar, como ya hace el notificator. Sin esto, el primer replay que no se puede publicar crea la serie directamente en 1, `increase()` no lo ve y la alerta de replay sin Kafka no se dispara.
  - Fix: si el producer de Kafka no se puede construir (por ejemplo, porque el host del broker no resuelve), el replay responde 503 con `Retry-After`, no 500.

Aplicaciones tocadas: **manager** (registro de contadores y el fix del replay). **notificator** y **gateway** no cambian. El resto es configuración de observabilidad: provisioning de Grafana, el dashboard `General` y `docker-compose.yml` (un volumen nuevo).

### Fuera de alcance

- Contact points y notificaciones (email, Slack, webhook). Las alertas se ven en la UI de Grafana (Alerting y el panel de `General`). El contact point por defecto queda sin configurar.
- Notification policies y routing por severidad: la severidad es solo un label.
- Prometheus rules y Alertmanager: no se agregan contenedores.
- Métricas nuevas en las aplicaciones. Las alertas usan solo las métricas que ya existen.
- Alerta sobre el p95 de punta a punta: por diseño incluye reintentos de hasta ~31 minutos, así que con un solo cliente caído se dispara.
- Alertas sobre `notificator_discarded_total` y `notificator_replays_total`: muestran la idempotencia funcionando, no una falla.
- Alertas sobre logs (Loki).
- Rebalanceos del consumer group y picos de `NOT_SUBSCRIBED`.

## Capabilities

### New Capabilities

- `operations-alerts`: reglas de alerta de Grafana sobre la entrega de notificaciones y la plataforma, con su severidad y su visualización en el dashboard `General`.

### Modified Capabilities

- `notification-event-replay`: el 503 cuando Kafka no está disponible también cubre el caso en que el producer no se puede crear.

## Impact

- **Código del manager**: `NotificationEventReplayService` y `KafkaReplayRequestPublisher`, más sus tests.
- **Observabilidad**: `observability/grafana/provisioning/alerting/` (archivo nuevo con las reglas), `observability/grafana/dashboards/general.json` (panel de alertas) y `docker-compose.yml` (montar el directorio de alerting en Grafana).
- **README**: sección de observabilidad.
- **Dependencias nuevas**: ninguna. El alerting ya viene con Grafana 12.1.
- **Orden de archivado**: usa las métricas y el dashboard `General` de `add-grafana-dashboards`, que debe archivarse antes que este change.

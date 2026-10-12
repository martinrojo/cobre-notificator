# Spec Delta

## MODIFIED Requirements

### Requirement: Lectura legible de logs
Los logs SHALL poder leerse como una línea por evento, sin JSON, tanto en el dashboard `Logs` de Grafana como en `docker compose logs`, sin cambiar el formato JSON que escriben las apps. Cada línea SHALL mostrar nivel, logger, mensaje, `event_id` y `client_id` cuando existan. Una excepción SHALL quedar en la misma línea que su evento, con los saltos de línea escapados.

#### Scenario: Log de entrega legible en Grafana
- **GIVEN** el notificator entrega un evento `EVT1` de `CLIENT001`
- **WHEN** se mira el dashboard `Logs`
- **THEN** aparece la línea `INFO DeliveryService Event delivered event_id=EVT1 client_id=CLIENT001`

#### Scenario: Excepción en una sola línea
- **GIVEN** un log de nivel `ERROR` con una excepción adjunta (por ejemplo, un mensaje inválido enviado al DLT)
- **WHEN** se lee en el dashboard `Logs` o con `docker compose logs --no-log-prefix notificator | jq -Rr -f observability/logs.jq`
- **THEN** el evento ocupa una sola línea que termina con `exception=...`, con el stack trace con `\n` escapados, y la cantidad de líneas formateadas es igual a la de líneas JSON

#### Scenario: Error como texto y como excepción
- **GIVEN** un log con el key-value `error` como string (`Event failed`) y otro con una excepción en el objeto ECS `error`
- **WHEN** se formatean
- **THEN** el primero muestra `error=<motivo>` y el segundo `exception=<stack trace>`, sin fallar

### Requirement: Grafana provisionado como código
Grafana SHALL arrancar con los datasources Prometheus y Loki y con los dashboards `Apps` (métricas) y `Logs` cargados desde archivos del repo. La contraseña del admin SHALL venir de una variable de entorno, nunca del repo.

#### Scenario: Dashboard disponible al arrancar
- **GIVEN** un volumen de Grafana vacío
- **WHEN** se levanta el stack y se abre el dashboard provisionado `Apps`
- **THEN** muestra, sin configuración manual, entregas por `outcome` y por `client_id`, mensajes en DLT, latencia p95 del webhook, replays del manager y requests HTTP por status, y no tiene panel de logs (los logs están en el dashboard `Logs`)

#### Scenario: Latencia p95 del webhook
- **GIVEN** entregas al webhook con resultado `DELIVERED` y `RETRYING`
- **WHEN** se mira el panel de latencia del webhook
- **THEN** muestra el p95 por `outcome`, calculado del histograma `notificator_webhook_latency_seconds_bucket`

#### Scenario: Credenciales de admin
- **GIVEN** `GRAFANA_ADMIN_PASSWORD` definido en `.env`
- **WHEN** se ingresa a Grafana con `admin` y esa contraseña
- **THEN** el login es exitoso, y el valor no aparece en ningún archivo commiteado salvo como ejemplo en `.env.example`

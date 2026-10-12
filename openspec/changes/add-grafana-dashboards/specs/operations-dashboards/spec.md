# Spec Delta

## Purpose

Dashboards de operación en Grafana para seguir la entrega de notificaciones: salud general, comportamiento interno del notificator, la vista de un cliente puntual y el estado de Kafka.

## ADDED Requirements

### Requirement: Dashboards de operación provisionados
Grafana SHALL arrancar con los dashboards `General`, `Notificator`, `Cliente` y `Kafka`, cargados desde archivos del repo sin configuración manual, con el tag `cobre` y sin permitir guardar cambios desde la UI. El dashboard `Apps` SHALL no existir.

#### Scenario: Dashboards disponibles al arrancar
- **GIVEN** un volumen de Grafana vacío y el stack levantado con `docker compose up -d`
- **WHEN** se buscan dashboards con el tag `cobre`
- **THEN** aparecen `General`, `Notificator`, `Cliente`, `Kafka` y `Logs`, y no aparece `Apps`

### Requirement: Tasa de éxito de entrega
Los dashboards SHALL calcular la tasa de éxito como entregas `DELIVERED` sobre la suma de `DELIVERED` y `FAILED` en el rango elegido. `NOT_SUBSCRIBED` y los intentos que se reintentan no entran en la cuenta. Un evento cerrado como `FAILED` por el job de recuperación cuenta como `FAILED`.

#### Scenario: Éxito con fallos y no suscriptos
- **GIVEN** en el rango elegido 9 eventos terminan en `DELIVERED`, 1 en `FAILED` (tras recibir HTTP 503 en los 4 intentos) y 5 en `NOT_SUBSCRIBED`
- **WHEN** se mira la tasa de éxito del dashboard `General`
- **THEN** muestra 90%

#### Scenario: FAILED del job de recuperación
- **GIVEN** un evento en `RETRYING` con `attempts=4` sin avanzar, que el job de recuperación pasa a `FAILED`
- **WHEN** se mira la tasa de éxito
- **THEN** ese evento cuenta en el denominador como `FAILED`

### Requirement: Dashboard General
El dashboard `General` SHALL mostrar: tasa de éxito, entregas `DELIVERED` por minuto, mensajes llegados al DLT en la última hora, p95 de la latencia del webhook y de la latencia de punta a punta, lag total de los consumer groups del notificator y eventos del job de recuperación por acción. Una fila colapsada SHALL mostrar targets arriba, requests HTTP por status y replays del manager.

#### Scenario: Evento al DLT contado desde el primero
- **GIVEN** el notificator recién arrancado y ningún mensaje en el DLT desde entonces
- **WHEN** un evento agota sus 4 intentos con HTTP 500 y llega al DLT
- **THEN** el panel de DLT de la última hora muestra 1, no 0

#### Scenario: Latencia de punta a punta con reintentos
- **GIVEN** `CLIENT002` responde HTTP 503 al intento 1 y HTTP 200 al intento 2, con un delay de reintento de 60 s
- **WHEN** el evento queda en `DELIVERED`
- **THEN** la latencia de punta a punta registrada para ese evento es mayor o igual a 60 s, mientras que la del webhook registra solo la duración de cada POST

#### Scenario: Lag cuando el notificator no consume
- **GIVEN** el notificator detenido y el simulador publicando en `transactions.events.v1`
- **WHEN** se mira el panel de lag del dashboard `General`
- **THEN** el lag crece en vez de desaparecer

### Requirement: Dashboard Notificator
El dashboard `Notificator` SHALL mostrar: intentos al webhook por resultado en el tiempo, intentos por número de intento (1 a 4), eventos que terminan en `DELIVERED`, `FAILED` y `NOT_SUBSCRIBED`, tasa de `NOT_SUBSCRIBED`, escrituras condicionales descartadas por motivo y errores de persistencia contra errores del webhook.

#### Scenario: Distribución por número de intento
- **GIVEN** `CLIENT002` responde HTTP 503 a todos los intentos
- **WHEN** sus eventos agotan los reintentos
- **THEN** el panel de intentos muestra intentos 1, 2, 3 y 4 con resultado reintentable, y el panel de estados finales suma esos eventos en `FAILED`

#### Scenario: Tasa de NOT_SUBSCRIBED
- **GIVEN** en el rango elegido 8 eventos terminan en `DELIVERED`, 0 en `FAILED` y 2 en `NOT_SUBSCRIBED`
- **WHEN** se mira la tasa de `NOT_SUBSCRIBED`
- **THEN** muestra 20%

#### Scenario: Escrituras con 0 filas
- **GIVEN** un evento duplicado en `transactions.events.v1` y un mensaje del DLT de un intento que ya no es el vigente
- **WHEN** el notificator los procesa
- **THEN** el panel de 0 filas muestra un descarte `duplicate` y uno `dlt_ignored`

#### Scenario: Persistencia contra webhook
- **GIVEN** MariaDB detenida mientras llegan eventos, y luego levantada, con `CLIENT003` respondiendo HTTP 500
- **WHEN** se mira el panel de errores
- **THEN** muestra errores de persistencia mientras la base estuvo caída y errores del webhook de `CLIENT003` como series separadas

### Requirement: Dashboard Cliente
El dashboard `Cliente` SHALL tener arriba una variable `client` de selección única con los `client_id` que tienen entregas. Todos sus paneles SHALL filtrar por ese cliente: tasa de éxito y volumen, intentos fallidos por código HTTP o error de transporte, p95 de la latencia de su webhook y un panel de logs con sus intentos fallidos.

#### Scenario: Fallos por código HTTP y timeout
- **GIVEN** el mock configurado para que `CLIENT002` responda HTTP 503, `CLIENT003` tarde 15 s (más que el read-timeout de 10 s) y `CLIENT004` responda HTTP 404
- **WHEN** se elige cada cliente en `$client`
- **THEN** el panel de fallos muestra `503` para `CLIENT002`, `timeout` para `CLIENT003` y `404` para `CLIENT004`

#### Scenario: Aislamiento por cliente
- **GIVEN** `CLIENT001` con todas sus entregas en `DELIVERED` (HTTP 200) y `CLIENT002` con fallos HTTP 503
- **WHEN** se elige `CLIENT001` en `$client`
- **THEN** ningún panel muestra datos de `CLIENT002`: la tasa de éxito es 100%, el panel de fallos está vacío y el panel de logs no tiene líneas con `client_id=CLIENT002`

#### Scenario: Logs de intentos fallidos
- **GIVEN** `CLIENT002` respondiendo HTTP 503
- **WHEN** se elige `CLIENT002` en `$client`
- **THEN** el panel de logs muestra, del más reciente al más antiguo, cada intento fallido con su `event_id`, el número de intento y el motivo (`HTTP 503`), y los eventos que terminaron en `FAILED` o en el DLT

### Requirement: Dashboard Kafka
El dashboard `Kafka` SHALL mostrar el lag por consumer group y partición, medido del lado del broker; los mensajes por minuto escritos en cada tópico `notificator.retry.v1-0..2` y en `notificator.dlt.v1`; y los rebalanceos por consumer group del notificator.

#### Scenario: Mensajes en retry y DLT
- **GIVEN** `CLIENT002` responde HTTP 503 a todos los intentos
- **WHEN** sus eventos recorren los reintentos
- **THEN** el panel muestra mensajes en `notificator.retry.v1-0`, `-1` y `-2` y luego en `notificator.dlt.v1`

#### Scenario: Lag por partición
- **GIVEN** el notificator detenido y eventos publicados en `transactions.events.v1`
- **WHEN** se mira el panel de lag
- **THEN** muestra el lag del consumer group `notificator` separado por partición 0, 1 y 2

#### Scenario: Rebalanceo
- **GIVEN** el notificator consumiendo
- **WHEN** se reinicia el contenedor del notificator
- **THEN** el panel de rebalanceos muestra rebalanceos en los consumer groups del notificator

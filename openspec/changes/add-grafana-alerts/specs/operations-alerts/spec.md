# Spec Delta

## Purpose

Alertas de Grafana que avisan de fallas en la entrega de notificaciones y en la plataforma sin que nadie tenga que estar mirando los dashboards. Cada alerta tiene una severidad según de quién es la falla: nuestra o del webhook de un cliente.

## ADDED Requirements

### Requirement: Alertas provisionadas
Grafana SHALL arrancar con las reglas de alerta de este spec, cargadas desde archivos del repo sin configuración manual y sin permitir editarlas desde la UI. Cada regla SHALL tener el label `severity` con `critical`, `warning` o `info`. Sin datos (por ejemplo, sin tráfico), una regla SHALL quedar en estado normal, no en alerta.

#### Scenario: Reglas disponibles al arrancar
- **GIVEN** un volumen de Grafana vacío y el stack levantado con `docker compose up -d`
- **WHEN** se abre Alerting → Alert rules
- **THEN** aparecen las reglas de este spec con su severidad, ninguna editable desde la UI

#### Scenario: Sin tráfico no hay alertas
- **GIVEN** el stack levantado sano y el simulador detenido
- **WHEN** pasan 10 minutos
- **THEN** ninguna regla está en alerta ni en error

### Requirement: Alerta de aplicación caída
Una alerta `critical` SHALL dispararse cuando Prometheus no puede scrapear el manager, el notificator o las métricas de Kafka (Alloy) durante 1 minuto. El label `app` SHALL indicar cuál.

#### Scenario: Notificator detenido
- **GIVEN** el stack levantado sano
- **WHEN** se detiene el contenedor `notificator` por más de 1 minuto
- **THEN** se dispara la alerta de aplicación caída con `app=notificator`, y al levantarlo vuelve a normal

### Requirement: Alerta de notificator sin consumir
Una alerta `critical` SHALL dispararse cuando el lag del consumer group `notificator` sobre `transactions.events.v1` supera un umbral y sigue creciendo durante 5 minutos. El lag de los consumer groups de retry y del DLT SHALL no contar, porque incluye mensajes que esperan su backoff a propósito.

#### Scenario: Notificator detenido con el simulador publicando
- **GIVEN** el simulador publicando eventos
- **WHEN** se detiene el notificator por más de 5 minutos
- **THEN** se dispara la alerta de notificator sin consumir, además de la de aplicación caída

#### Scenario: Reintentos esperando su backoff
- **GIVEN** `CLIENT002` responde HTTP 503 y sus eventos esperan en los tópicos de retry
- **WHEN** el consumer group `notificator` está al día
- **THEN** la alerta de notificator sin consumir no se dispara

### Requirement: Alerta de errores de persistencia
Una alerta `critical` SHALL dispararse cuando el notificator registra errores de persistencia de forma sostenida durante 2 minutos.

#### Scenario: Base caída
- **GIVEN** el simulador publicando eventos
- **WHEN** se detiene MariaDB por más de 2 minutos
- **THEN** se dispara la alerta de errores de persistencia

### Requirement: Alerta de mensajes al DLT por error interno
Una alerta `critical` SHALL dispararse cuando llega al DLT al menos un mensaje con motivo `invalid` o `unexpected` en los últimos 10 minutos. Un mensaje con motivo `exhausted` (el webhook de un cliente agotó los reintentos) SHALL no dispararla.

#### Scenario: Mensaje inválido
- **WHEN** se publica en `transactions.events.v1` un mensaje que no es un evento válido y llega al DLT con motivo `invalid`
- **THEN** se dispara la alerta de DLT por error interno

#### Scenario: Reintentos agotados de un cliente
- **GIVEN** `CLIENT002` responde HTTP 503 en los 4 intentos
- **WHEN** el evento llega al DLT con motivo `exhausted` y queda en `FAILED`
- **THEN** la alerta de DLT por error interno no se dispara

### Requirement: Alerta de replay sin Kafka
Una alerta `critical` SHALL dispararse cuando el manager responde al menos un replay con 503 por no poder publicar en Kafka en los últimos 5 minutos.

#### Scenario: Kafka caído durante un replay
- **GIVEN** un evento `FAILED` de `CLIENT001` y Kafka detenido
- **WHEN** se pide `POST /notification_events/{notification_event_id}/replay` con `X-Client-Id: CLIENT001` y responde 503
- **THEN** se dispara la alerta de replay sin Kafka

#### Scenario: Primer replay fallido después de arrancar el manager
- **GIVEN** el manager recién arrancado, sin ningún replay desde entonces, y Kafka sin responder
- **WHEN** el primer replay responde 503
- **THEN** se dispara la alerta de replay sin Kafka

### Requirement: Alerta de muchos clientes fallando
Una alerta `warning` SHALL dispararse cuando más del 30% de los clientes con intentos al webhook en los últimos 10 minutos tiene más del 50% de sus intentos sin entregar, durante 5 minutos. Un solo cliente caído SHALL no dispararla mientras sea menos del 30% de los clientes activos.

#### Scenario: Todos los webhooks rechazan la entrega
- **GIVEN** el simulador publicando para `CLIENT001`..`CLIENT008`
- **WHEN** el mock responde HTTP 400 a todos los clientes por más de 5 minutos
- **THEN** se dispara la alerta de muchos clientes fallando

#### Scenario: Un solo cliente caído
- **GIVEN** el simulador publicando para `CLIENT001`..`CLIENT008`
- **WHEN** solo `CLIENT002` responde HTTP 503 y los demás HTTP 200
- **THEN** la alerta de muchos clientes fallando no se dispara, y se dispara la de circuito abierto de `CLIENT002`

### Requirement: Alerta de errores del manager
Una alerta `warning` SHALL dispararse cuando más del 5% de los requests al manager responden 5xx en los últimos 5 minutos. El 503 del replay sin Kafka SHALL no contar, porque tiene su propia alerta.

#### Scenario: Replay con Kafka caído
- **GIVEN** Kafka detenido
- **WHEN** todos los replays responden 503
- **THEN** la alerta de errores del manager no se dispara y sí la de replay sin Kafka

### Requirement: Alerta de eventos cerrados por el job de recuperación
Una alerta `warning` SHALL dispararse cuando el job de recuperación cierra al menos un evento como `FAILED` en los últimos 15 minutos. Los eventos que el job vuelve a publicar SHALL no dispararla.

#### Scenario: Evento colgado en el último intento
- **GIVEN** un evento en `RETRYING` con `attempts=4` que no avanza más allá de su umbral
- **WHEN** el job de recuperación lo pasa a `FAILED`
- **THEN** se dispara la alerta de eventos cerrados por el job de recuperación

### Requirement: Alerta de circuito abierto por cliente
Una alerta `info` SHALL dispararse por cada cliente cuyo circuito queda abierto o semiabierto durante más de 5 minutos. El label `client_id` SHALL indicar el cliente, y la alerta de un cliente SHALL no incluir a otros clientes.

#### Scenario: Un cliente con el webhook caído
- **GIVEN** el simulador publicando para `CLIENT001`..`CLIENT008`
- **WHEN** `CLIENT002` responde HTTP 503 por más de 5 minutos
- **THEN** se dispara una alerta de circuito abierto con `client_id=CLIENT002` y ninguna para otro cliente

#### Scenario: El cliente se recupera
- **GIVEN** la alerta de circuito abierto de `CLIENT002` disparada
- **WHEN** el webhook de `CLIENT002` vuelve a responder HTTP 200 y el circuito se cierra
- **THEN** la alerta de `CLIENT002` vuelve a normal

### Requirement: Alertas activas en el dashboard General
El dashboard `General` SHALL mostrar un panel con las alertas en curso, con su severidad.

#### Scenario: Alerta visible en General
- **GIVEN** la alerta de circuito abierto de `CLIENT002` disparada
- **WHEN** se abre el dashboard `General`
- **THEN** el panel de alertas la muestra con severidad `info` y `client_id=CLIENT002`

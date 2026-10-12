# Proposal

## Why

`transactions.events.v1` lo produce la plataforma, que no existe en este repo. Sin un productor no hay forma de ejercitar el notificator de punta a punta ni de ver carga sostenida en los dashboards (entregas por cliente, reintentos, DLT). Hace falta un simulador local que publique eventos realistas a un ritmo conocido.

## What Changes

- Nueva aplicación Java `transaction-simulator` (Spring Boot, sin endpoints de negocio) que publica eventos aleatorios en `transactions.events.v1`.
- Los eventos se reparten entre 8 clientes fijos: `CLIENT001` … `CLIENT008`.
- Tasa objetivo configurable, por defecto 3000 eventos por minuto (50/s) en total.
- La generación corre sobre virtual threads: un virtual thread por cliente.
- Cada mensaje respeta el contrato del tópico principal (JSON snake_case, key = `client_id`).
- Servicio nuevo en `docker-compose.yml`, detrás del profile `simulator` para que no arranque por defecto.

Aplicaciones afectadas: ninguna de las existentes (gateway, manager y notificator no cambian). Se agrega `transaction-simulator` como herramienta de desarrollo.

### Fuera de alcance

- Crear suscripciones para los 8 clientes: el simulator no escribe en la DB. Los eventos de clientes sin suscripción activa terminan en `NOT_SUBSCRIBED` según el comportamiento existente del notificator.
- Simular webhooks receptores.
- Publicar mensajes inválidos o duplicados a propósito.
- Métricas Prometheus y dashboard propios del simulator.
- Despliegue fuera del entorno local.

## Capabilities

### New Capabilities
- `transaction-simulation`: generación local de eventos sintéticos en `transactions.events.v1` (formato, clientes, tasa y ciclo de vida del productor).

### Modified Capabilities
<!-- Ninguna: el contrato de transactions.events.v1 (event-topics y el formato del mensaje) no cambia, el simulator solo lo cumple. -->

## Impact

- Código nuevo: `transaction-simulator/` (pom.xml, Dockerfile, src/).
- `docker-compose.yml`: servicio `transaction-simulator` con `profiles: [simulator]`, dependiente de `kafka-init`.
- Dependencias: solo del stack (Spring Boot 4, spring-kafka, Jackson). Sin dependencias nuevas.
- Carga: ~50 mensajes/s sobre el broker local y el notificator mientras corre.

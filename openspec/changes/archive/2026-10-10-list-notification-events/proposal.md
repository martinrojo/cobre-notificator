# Proposal

## Why

La API self-service tiene que permitir que cada cliente consulte sus notificaciones de eventos, filtrando por fecha de creación y estado de entrega (Task 2 del caso). Es el primer endpoint de negocio del manager, así que también fija cómo se identifica al cliente en cada request, mecanismo que más adelante va a resolver el JWT.

## What Changes

- Nuevo endpoint `GET /notification_events` en el manager, con query params opcionales `from`, `to` (comparan contra `created_at`) y `delivery_status`. Devuelve solo los eventos del cliente que hace el request.
- Nuevo filtro HTTP que identifica al cliente en todos los requests, salvo una lista explícita de rutas públicas (`/ping`). Por ahora toma el client_id del header `X-Client-Id`; si falta o viene vacío, responde `401` antes de cualquier otro procesamiento. Más adelante este filtro se reemplaza por la validación del JWT, que se va a hacer tanto en el gateway como en el manager.
- Errores de input (fecha mal formada, `delivery_status` desconocido, `from` posterior a `to`) responden `400` con un mensaje específico.
- El manager pasa a conectarse a MariaDB con su usuario de solo lectura.
- Seed de datos de ejemplo (`notification_events.json`) en `notificator/db/` para probar el endpoint en local.

Aplicaciones afectadas: **manager**. En notificator solo se agrega el script de seed en `notificator/db/`; su código no se toca. gateway no se toca.

### Fuera de alcance

- Paginación y su tamaño máximo configurable (va en un cambio aparte).
- `GET /notification_events/{id}` y el replay.
- Validación real del JWT (firma, expiración, claims).
- Métricas específicas del endpoint.

## Capabilities

### New Capabilities
- `client-identification`: identificación del cliente que hace cada request a la API self-service y rechazo de los requests sin cliente.
- `notification-event-query`: consulta de las notificaciones de eventos de un cliente, con filtros por fecha de creación y estado de entrega.

### Modified Capabilities

Ninguna.

## Impact

- `manager/`: dependencias nuevas `spring-boot-starter-jdbc` y el driver de MariaDB; nuevo código de dominio, aplicación y adapters (web y JDBC).
- `manager/src/main/resources/application.yml`: datasource configurado con variables de entorno.
- `docker-compose.yml`: el servicio `manager` recibe las credenciales de DB y depende de que `mariadb` esté healthy.
- `notificator/db/03-seed-notification-events.sql`: datos de ejemplo. Como los scripts de init solo corren con el volumen vacío, hay que recrearlo con `docker compose down -v`.

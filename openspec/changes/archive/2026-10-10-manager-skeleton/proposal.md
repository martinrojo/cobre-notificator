# Proposal

## Why

El manager todavía no existe (`manager/` está vacío). Necesitamos una base compilable, dockerizada y con la estructura hexagonal definida, para que los cambios siguientes (consulta de eventos, replay) solo agreguen lógica y no discutan estructura.

## What Changes

- Nuevo proyecto Maven en `manager/` con Java 25 y Spring Boot 4.
- Estructura de paquetes hexagonal (domain / application / infrastructure), sin clases de relleno.
- Endpoint `GET /ping` que responde `200` con cuerpo `pong`, como verificación mínima de que la app está viva.
- `fmt-maven-plugin` (Spotify) configurado con el goal `check`: el build falla si el código no está formateado.
- Logs estructurados en JSON a stdout desde el arranque.
- `manager/Dockerfile` multi-stage que compila con Maven dentro de la imagen.
- Servicio `manager` agregado al `docker-compose.yml` de la raíz.

Aplicaciones afectadas: **manager** (nueva). gateway y notificator no se tocan.

### Fuera de alcance

- Endpoints de negocio (`/notification_events`, replay).
- Conexión a MariaDB y a Kafka (sin datasource ni dependencias de JDBC/Kafka todavía).
- Actuator, Micrometer/Prometheus y dashboards.
- Paso por el gateway y manejo de `X-Client-Id`.

## Capabilities

### New Capabilities
- `manager-ping`: chequeo de vida del manager vía `GET /ping`.

### Modified Capabilities

Ninguna.

## Impact

- Código nuevo: `manager/` (pom.xml, código fuente, tests, Dockerfile).
- `docker-compose.yml`: nuevo servicio `manager` expuesto en el puerto 9290.
- Dependencias: Spring Boot 4 (starter webmvc y su starter de test) y `fmt-maven-plugin`, ya previsto por las convenciones del proyecto.

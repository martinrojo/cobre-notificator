# Proposal

## Why

El circuit breaker por cliente usa una ventana de 20 llamadas (`COUNT_BASED`, mínimo 5). Una ventana por cantidad cubre un tiempo distinto según el volumen del cliente. Para un cliente de alto volumen son un instante, y un blip de un segundo abre el circuito. Para uno de bajo volumen son horas, así que decide con fallos viejos. El volumen varía mucho entre clientes, y una sola config por cantidad no sirve para todos.

## What Changes

- **Ventana por tiempo:** la config por defecto del circuito pasa de una ventana de 20 llamadas a una de 60 segundos (`TIME_BASED`). El mínimo de llamadas sube de 5 a 10. Así la decisión deja de depender del volumen del cliente. Umbral, espera en abierto, llamadas de prueba y clasificación de fallos no cambian.
- **Perfiles de circuito:** se pueden definir perfiles con nombre que heredan del default y cambian solo la ventana y el mínimo de llamadas. Cada `client_id` se asigna a un perfil por configuración, y un cliente sin perfil asignado usa el default.
- **Perfil de ejemplo:** se agrega `bajo-volumen`, con ventana de 300 s y mínimo de 5 llamadas, para clientes que con el default nunca alcanzarían el mínimo. En el entorno local se asigna a `CLIENT008`.

## Capabilities

### New Capabilities

(ninguna)

### Modified Capabilities

- `notification-delivery`:
  - **Requisito modificado:** "Resultados que cuentan como fallo del circuito". La tasa de fallo pasa a calcularse sobre una ventana de tiempo, no sobre las últimas N llamadas.
  - **Requisito nuevo:** perfiles de circuito por cliente.

  Este change depende de `webhook-circuit-breaker`, que agrega esos requisitos y todavía no está archivado. Hay que archivar ese change antes que este.

## Impact

- **Aplicaciones:** solo el notificator. El gateway y el manager no cambian.
- **Código:** no hay cambios en Java. `RestClientWebhookClient` ya obtiene el breaker con `circuitBreakers.circuitBreaker(clientId)`, y Resilience4j toma la config de `instances.<clientId>` si existe, o la de `default` si no. El cambio queda en `application.yml`, `docker-compose.yml` y los tests de arranque.
- **Dependencias:** ninguna nueva.
- **Datos:** sin cambios.
- **Comportamiento visible:**
  - Un cliente con menos de 10 llamadas en 60 s nunca abre el circuito. Sus fallos siguen los reintentos normales.
  - Un cliente que hace timeout en todo ocupa la partición hasta 10 × (250 + 500 ms), unos 7,5 s, antes de abrir. Antes eran unos 4 s.
- **Documentación:** el README menciona la ventana y la asignación de perfiles.

**Fuera de alcance:**

- Configuración por cliente en la base o editable en caliente, y API en el manager.
- Timeouts del webhook por cliente: siguen siendo globales.
- Validar en el arranque que un perfil no rompa la regla `wait < retry.delay`. Los perfiles no redefinen la espera; la heredan.

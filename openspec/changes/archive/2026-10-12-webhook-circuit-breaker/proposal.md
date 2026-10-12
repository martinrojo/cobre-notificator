# Proposal

## Why

La entrega al webhook es síncrona, y los mensajes de `transactions.events.v1` usan `client_id` como key: varios clientes comparten cada una de las 3 particiones y su hilo consumidor. Si el webhook de un cliente está caído o lento, cada evento suyo ocupa el hilo hasta el timeout (connect + read), y los eventos de otros clientes de la misma partición esperan detrás. Hoy nada corta esa situación: un cliente caído degrada la entrega de otros. El caso pide reintentos eficientes y que un cliente nunca afecte a otro.

## What Changes

- Circuit breaker por `client_id` sobre la llamada al webhook en el notificator, con estado en memoria de cada instancia. El circuito de un cliente nunca abre, cierra ni consume el cupo de prueba del circuito de otro.
- Solo cuentan como fallo del circuito las señales de cliente no disponible: timeout (conexión o lectura), error de conexión, 429, 502, 503 y 504. El resto cuenta como éxito, incluidos errores que sí se reintentan como 500 y 408: el cliente está vivo y responde rápido, así que seguir llamándolo no ocupa el hilo ni lo sobrecarga. Qué se reintenta y qué abre el circuito son clasificaciones distintas.
- Un intento rechazado por el circuito abierto no hace POST y se trata como error reintentable: el evento pasa a `RETRYING` con `last_error = "Circuit open"` y sigue el camino normal de reintentos. El intento ya reclamado se consume (no se devuelve) y la partición no se bloquea.
- El tiempo en estado abierto es menor que el primer delay de reintento (60 s), de modo que cada ola de reintentos encuentre el circuito semiabierto y pruebe el webhook con llamadas reales.
- Observabilidad: estado del circuito por cliente en Prometheus, el rechazo cuenta en el contador de intentos con `status="circuit_open"` (sin registrar latencia), log INFO en cada cambio de estado del circuito y panel en el dashboard de cliente de Grafana.

## Capabilities

### New Capabilities

(ninguna)

### Modified Capabilities

- `notification-delivery`: la petición al webhook puede ser rechazada por el circuit breaker del cliente sin POST. Se agregan requisitos de circuit breaker por cliente y de su efecto en los intentos, y se amplía la observabilidad con el estado del circuito.

## Impact

- **Aplicaciones**: solo el notificator. El gateway y el manager no cambian.
- **Código**: adapter HTTP del webhook (`RestClientWebhookClient`), modelo `WebhookResult` y `DeliveryOutcome`, registro del intento en `DeliveryService`, `application.yml`.
- **Dependencias**: ninguna nueva; `resilience4j-spring-boot4` ya está en el `pom.xml` (incluye el binder de Micrometer).
- **Datos**: sin cambios de esquema. `last_error` toma el nuevo valor `Circuit open`.
- **Observabilidad**: nueva métrica de estado del circuito por cliente y panel en `observability/grafana/dashboards/cliente.json`.
- **Comportamiento visible**: un cliente con el webhook caído durante más de ~31 min (60 + 300 + 1500 s) termina en `FAILED` con menos POST reales que hoy; se recupera con el replay del manager, igual que ahora.

**Fuera de alcance**:
- Estado del circuito compartido entre instancias (Redis u otro store).
- Pausar la partición o retener el mensaje mientras el circuito está abierto.
- Bulkhead o límite de concurrencia por cliente, y rate limiting hacia el webhook.
- Exponer el estado del circuito en la API del manager.
- Configuración de umbrales por cliente: todos los clientes usan la misma.

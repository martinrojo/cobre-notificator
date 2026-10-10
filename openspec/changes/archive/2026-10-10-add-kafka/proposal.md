# Proposal

## Why

El notificator consume eventos desde Kafka y el manager publica replays en Kafka, pero hoy no hay broker en el entorno local. Antes de implementar consumidores y productores necesitamos el broker en el `docker-compose.yml` y los tópicos creados de forma reproducible, con nombres, particiones y retenciones definidas.

## What Changes

- Nuevo servicio `kafka` en `docker-compose.yml`: un nodo en modo KRaft (sin ZooKeeper), con healthcheck y volumen persistente.
- Creación automática de tópicos deshabilitada en el broker. Un tópico que no exista hace fallar al cliente, en lugar de crearse con la configuración por defecto.
- Script idempotente de creación de tópicos (`kafka/create-topics.sh`), ejecutado por un servicio one-shot `kafka-init` del compose. Las apps que usen Kafka dependen de que ese servicio termine bien.
- Tópicos, todos con 3 particiones:

  | Tópico | Rol | Retención |
  |---|---|---|
  | `transactions.events.v1` | principal, inicio del flujo (lo produce la plataforma) | 7 días |
  | `notificator.retry.v1-0` | reintento 1 (60 s) | 1 día |
  | `notificator.retry.v1-1` | reintento 2 (300 s) | 1 día |
  | `notificator.retry.v1-2` | reintento 3 (1500 s) | 1 día |
  | `notificator.dlt.v1` | DLT, reintentos agotados | 14 días |
  | `notificator.replay.v1` | replays pedidos desde el manager | 7 días |

- **BREAKING (convención)**: se reemplazan los nombres `notification`, `notification-replay`, `-retries-*` y `-failed` de `openspec/config.yaml` por los de la tabla. El replay deja de tener retry y DLT propios y comparte los del flujo principal. Hoy ningún código usa los nombres viejos.
- `.env.example` y README documentan cómo conectarse al broker desde el host.

Aplicaciones afectadas: ninguna en código. Solo cambian la infraestructura (compose y script) y la convención de nombres. gateway, manager y notificator no se tocan.

### Fuera de alcance

- Productores y consumidores: listener del notificator, `@RetryableTopic`, handler del DLT y publicación del replay en el manager.
- Contrato (schema) del mensaje en `transactions.events.v1` y Schema Registry.
- Seguridad del broker (SASL/TLS, ACLs). Localmente el broker no tiene autenticación; en producción se documenta como pendiente.
- Alta disponibilidad: localmente hay un solo broker con replication factor 1.
- Métricas del broker en Prometheus y Grafana, y el lag por consumer group.
- Herramienta o script para publicar eventos de prueba.

## Capabilities

### New Capabilities
- `event-topics`: tópicos de Kafka del flujo de notificaciones, con nombres, particiones y retención, y cómo se aprovisionan en el entorno local.

### Modified Capabilities

Ninguna.

## Impact

- `docker-compose.yml`: servicios `kafka` y `kafka-init`, y volumen `kafka-data`.
- Archivo nuevo: `kafka/create-topics.sh`.
- `openspec/config.yaml`: sección "Notificator y Kafka" con los nombres nuevos.
- `.env.example` y `README.md`.
- Dependencias: imagen oficial `apache/kafka`, prevista en el stack. No se agregan dependencias Java.

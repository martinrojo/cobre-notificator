# Design

## Context

`docker-compose.yml` hoy levanta solo `mariadb` y `manager`. Las convenciones de Kafka de `openspec/config.yaml` usan otros nombres (ver proposal.md, What Changes). Ninguna app depende todavía de Kafka, así que el cambio es solo infraestructura y no requiere migración de código.

## Goals / Non-Goals

**Goals:**
- `docker compose up` deja el broker listo y con los tópicos de la spec `event-topics`, sin pasos manuales.
- La configuración de los tópicos vive en un solo script versionado.

**Non-Goals:**
- Un compose apto para producción, con múltiples brokers, TLS o SASL.
- Gestionar los tópicos desde las apps (`NewTopic` beans o auto-creación de `@RetryableTopic`).

## Decisions

### 1. Imagen `apache/kafka:4.1.0` en modo KRaft, un solo nodo
Es la imagen oficial del proyecto Apache. En Kafka 4.x ZooKeeper ya no existe, así que el compose tiene un contenedor menos. Un mismo nodo cumple los roles `broker,controller`.
- Alternativa: `bitnami/kafka`. Se descarta porque Bitnami restringió sus imágenes públicas gratuitas.
- Alternativa: `confluent-local`. Se descarta porque trae extras (REST proxy) que no usamos.
- Versión fijada y no `latest`. Si al implementar hay un patch 4.1.x más nuevo, se puede usar.

### 2. Listeners
| Listener | Dirección anunciada | Uso |
|---|---|---|
| `PLAINTEXT` | `kafka:9092` | apps y `kafka-init` dentro de la red del compose |
| `CONTROLLER` | `kafka:9093` | quórum KRaft, no se publica |
| `EXTERNAL` | `localhost:9094` | herramientas desde el host, publicado en `127.0.0.1:9094` |

Como hay un solo nodo, `offsets.topic.replication.factor`, `transaction.state.log.replication.factor` y `transaction.state.log.min.isr` van en 1. `auto.create.topics.enable=false` (requirement "No implicit topic creation"). El `CLUSTER_ID` va fijo en el compose: identifica el clúster y no es un secreto.

### 3. Aprovisionamiento con un servicio one-shot `kafka-init`
- Usa la misma imagen `apache/kafka`, porque ya trae `kafka-topics.sh` y `kafka-configs.sh`. Monta `./kafka` en modo `:ro` y ejecuta `kafka/create-topics.sh`.
- Arranca con `depends_on: kafka: service_healthy`. El healthcheck del broker es `kafka-broker-api-versions.sh --bootstrap-server localhost:9092`.
- Las apps que usen Kafka, cuando existan, declaran `depends_on: kafka-init: service_completed_successfully`.
- Alternativa: beans `NewTopic` en el notificator. Se descarta porque mezcla la administración de tópicos con la app, le exige permisos de admin al usuario Kafka de la app y deja los tópicos sin crear hasta que el notificator arranca. Además `transactions.events.v1` no le pertenece al notificator.
- Alternativa: script en el `command` del contenedor de Kafka. Se descarta porque no permite el `depends_on` por finalización.

### 4. Script idempotente y POSIX
Por cada tópico, el script corre:
1. `kafka-topics.sh --create --if-not-exists --partitions 3 --replication-factor 1 --config retention.ms=… --config cleanup.policy=delete`
2. `kafka-configs.sh --alter --entity-type topics --entity-name … --add-config retention.ms=…,cleanup.policy=delete`. Este paso corrige el drift si alguien cambió la retención a mano.

Usa `#!/bin/sh` con `set -eu`, sin depender de que la imagen tenga bash. El primer error corta el script con exit ≠ 0. Una función `topic <name> <retention_ms>` y seis llamadas: sin archivo de configuración aparte.

El script no reduce particiones porque Kafka no lo permite, y tampoco las aumenta. Un tópico existente con otro número de particiones se deja como está; aumentarlas cambiaría el particionado por `client_id` y rompería el orden por cliente.

### 5. Nombres
- Los retry topics se nombran por índice (`-retry-0..2`), equivalente a `SUFFIX_WITH_INDEX_VALUE`. Si cambian los delays, no hay que renombrar tópicos.
- Los tópicos internos usan el prefijo `notificator.` y no derivan del nombre de `transactions.events.v1`: son del notificator, no del dominio de transacciones. Así otros consumidores del principal pueden tener sus propios retries sin colisiones.
- Hay un solo retry/DLT, compartido por el flujo principal y el replay. El consumidor de `notificator.replay.v1` delega en el mismo pipeline de entrega. Con 4 tópicos menos, la lógica de reintento queda en un solo lugar.
- Cuando se implemente el notificator, los sufijos de `@RetryableTopic` no alcanzan (derivan del tópico principal). Se usará un `RetryTopicNamesProviderFactory` propio que devuelva `notificator.retry.v1-<n>` y `notificator.dlt.v1`, con `autoCreateTopics="false"`. Queda registrado en `config.yaml`.

### 6. Retenciones
Son las de la spec. La de los retries (1 día) es mucho mayor que el delay máximo (1500 s), así que hay margen si el notificator está caído varias horas. La del DLT (14 días) es solo para auditoría y diagnóstico: el replay se hace desde `notification_event` en la DB, no releyendo el DLT.

## Risks / Trade-offs

- [Broker sin autenticación ni TLS: OWASP A05:2021 Security Misconfiguration] → El listener externo se publica solo en `127.0.0.1`, no en `0.0.0.0`. Producción con SASL/TLS y ACLs por app queda fuera de alcance y documentada en el README.
- [Replication factor 1: si se pierde el volumen, se pierden los mensajes] → Aceptable en local. En producción, RF=3 y `min.insync.replicas=2`; solo se documenta.
- [`kafka-init` falla y las apps no arrancan] → Es el comportamiento buscado (fail fast). El log de `kafka-init` muestra qué tópico falló.
- [Partitions desalineadas en un volumen viejo] → El script no las toca. Si hace falta, se baja con `docker compose down -v`.
- [Una app crea tópicos por su cuenta] → `auto.create.topics.enable=false` en el broker y `autoCreateTopics="false"` en la convención.

## Migration Plan

No hay datos previos. Para rollback se quitan los servicios del compose y se corre `docker compose down -v`.

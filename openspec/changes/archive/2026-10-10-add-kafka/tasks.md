# Tasks

## 1. Broker

- [x] 1.1 Agregar el servicio `kafka` (`apache/kafka:4.1.0`, KRaft de un nodo) a `docker-compose.yml` con:
  - los listeners `PLAINTEXT://kafka:9092`, `CONTROLLER://kafka:9093` y `EXTERNAL://localhost:9094`, este último publicado en `127.0.0.1:9094`;
  - `auto.create.topics.enable=false` y los replication factors internos en 1;
  - un `CLUSTER_ID` fijo, un healthcheck con `kafka-broker-api-versions.sh` y el volumen `kafka-data`.

  Verificar con `docker compose up -d kafka`: el servicio debe quedar `healthy` en `docker compose ps`.
- [x] 1.2 Verificar que la auto-creación está deshabilitada: `kafka-console-producer.sh --topic transactions.event.v1` falla con un error de tópico desconocido, y `kafka-topics.sh --list` no muestra ese tópico.

## 2. Tópicos

- [x] 2.1 Crear `kafka/create-topics.sh` (POSIX `sh`, `set -eu`, bootstrap desde `KAFKA_BOOTSTRAP_SERVERS` con default `kafka:9092`):
  - una función `topic <name> <retention_ms>` que hace `kafka-topics.sh --create --if-not-exists` (3 particiones, RF 1, `cleanup.policy=delete`) y después `kafka-configs.sh --alter --add-config retention.ms=…,cleanup.policy=delete`;
  - seis llamadas, una por tópico de la spec `event-topics`;
  - un comentario `ponytail:` sobre RF 1, que en producción va RF 3 con `min.insync.replicas=2`.

  Verificar con `sh -n kafka/create-topics.sh`.
- [x] 2.2 Agregar el servicio `kafka-init` a `docker-compose.yml`: misma imagen, `./kafka:/scripts:ro`, `command: sh /scripts/create-topics.sh`, `depends_on: kafka: service_healthy` y `restart: "no"`. Verificar con `docker compose up kafka-init` (exit 0) y `kafka-topics.sh --describe`: deben aparecer los 6 tópicos con `PartitionCount: 3`.
- [x] 2.3 Verificar la retención de cada tópico con `kafka-configs.sh --describe --entity-type topics --entity-name <topic>`. Valores esperados: 604800000 en el principal y en `notificator.replay.v1`, 86400000 en los `notificator.retry.v1-*` y 1209600000 en `notificator.dlt.v1`.
- [x] 2.4 Verificar la idempotencia y la corrección de drift:
  1. Cambiar a mano `retention.ms=3600000` en `transactions.events.v1`.
  2. Correr `docker compose up kafka-init` de nuevo. Debe terminar con exit 0.
  3. Comprobar que la retención volvió a 604800000.
- [x] 2.5 Verificar el fail fast: con `kafka` detenido, `docker compose run --rm --no-deps kafka-init` debe terminar con exit ≠ 0.

## 3. Convenciones y documentación

- [x] 3.1 Actualizar la sección "Notificator y Kafka" de `openspec/config.yaml`:
  - nombres nuevos de tópicos;
  - un solo retry/DLT compartido con el replay;
  - nombres fijos de retry/DLT vía `RetryTopicNamesProviderFactory` propio y `autoCreateTopics="false"`;
  - que el manager publica los replays en `notificator.replay.v1`.

  Ajustar también la línea del endpoint de replay en "API del manager". Verificar con `openspec validate add-kafka`.
- [x] 3.2 Agregar una sección Kafka al `README.md`:
  - tabla de tópicos y retenciones;
  - conexión desde el host (`localhost:9094`);
  - comandos de `--describe`;
  - cómo reaprovisionar (`docker compose up kafka-init`);
  - nota de producción: SASL/TLS, ACLs, RF 3.

  Ejecutar los comandos del README tal como están escritos.
- [x] 3.3 Si se agrega alguna variable a `docker-compose.yml`, por ejemplo `KAFKA_BOOTSTRAP_SERVERS`, sumarla a `.env.example`. Verificar con `docker compose config`: no debe haber variables sin definir.

## 4. Integración

- [x] 4.1 Partiendo de cero (`docker compose down -v && docker compose up -d`), verificar que `mariadb`, `kafka` y `manager` quedan arriba, `kafka-init` termina con exit 0 y `curl localhost:9290/ping` responde `pong`.

## Workflow follow-up

- Archivar el cambio una vez revisado.
- Agregar `depends_on: kafka-init: service_completed_successfully` cuando notificator y manager integren Kafka, en sus propios cambios.

# Tasks

## 1. Proyecto

- [x] 1.1 Crear `transaction-simulator/pom.xml` (parent Spring Boot 4.1.1, Java 25, `spring-boot-starter-kafka`, `spring-boot-starter-test`, `fmt-maven-plugin` check, `finalName app`) y `SimulatorApplication`; verificar con `mvn -q verify` en `transaction-simulator/`
- [x] 1.2 `application.yml`: `web-application-type: none`, Jackson `SNAKE_CASE`, producer String/String con `acks=all`, idempotencia y `max.block.ms: 1000`, logging estructurado JSON igual al del manager, y `simulator.topic/events-per-minute/clients` con env `SIMULATOR_EVENTS_PER_MINUTE`; verificar que la app arranca contra Kafka local (`localhost:9094`)

## 2. Generación de eventos

- [x] 2.1 Record `SimulatorProperties` (`@ConfigurationProperties("simulator")`) con validación `events-per-minute > 0` y `clients > 0`, y cálculo del intervalo por cliente (`60s × clients / eventsPerMinute`); test JUnit: 3000/8 → 160 ms, 600/8 → 800 ms, valor 0 → excepción
- [x] 2.2 Record `SimulatedEvent` y generador puro sobre `RandomGenerator` (`EVT-<uuid>`, tipo de los 10 de `notification_events.json`, `content` sintético con monto, `created_at` ahora) y lista `CLIENT001`..`CLIENT00N`; test JUnit con semilla: ids ≤ 64 chars y únicos en 10 000 eventos, tipos dentro de la lista, 8 clientes exactos, JSON serializado en snake_case con los 5 campos

## 3. Publicación con virtual threads

- [x] 3.1 `ApplicationRunner` que arranca un virtual thread por cliente con pacing por deadline (reset si atrasa más de un intervalo) y `kafkaTemplate.send(topic, clientId, json)` asíncrono; test JUnit + Mockito con `KafkaTemplate` mockeado: cada envío usa key = `client_id` del valor, y un futuro fallido no detiene el loop
- [x] 3.2 Logs: INFO al arrancar (tasa, clientes, intervalo), ERROR por envío fallido con `event_id` y `client_id`, DEBUG por envío exitoso; verificar en el test de 3.1 (o por revisión) que ningún log incluye `content`

## 4. Docker y documentación

- [x] 4.1 `transaction-simulator/Dockerfile` multi-stage igual al del manager (sin `EXPOSE`) y `.dockerignore`; verificar con `docker compose build transaction-simulator`
- [x] 4.2 Servicio `transaction-simulator` en `docker-compose.yml` con `profiles: [simulator]` y `depends_on kafka-init: service_completed_successfully`; verificar que `docker compose up` no lo arranca y `docker compose --profile simulator up` sí
- [x] 4.3 README: cómo levantar el simulator, cambiar la tasa y que sin suscripciones los eventos quedan en `NOT_SUBSCRIBED`; verificar que los comandos documentados funcionan tal cual

## 5. Verificación integrada

- [x] 5.1 Con el entorno levantado y `--profile simulator`, contar mensajes de `transactions.events.v1` durante 1 minuto (`kafka-get-offsets.sh` antes/después): 3000 ± 5 %, 8 `client_id` distintos, y el notificator no manda ninguno a `notificator.dlt.v1`

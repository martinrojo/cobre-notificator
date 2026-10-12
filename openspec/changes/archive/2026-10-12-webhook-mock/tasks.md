# Tasks

## 1. Proyecto

- [x] 1.1 Crear `webhook-mock/pom.xml` (parent Spring Boot 4.1.1, Java 25, `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-test`, `fmt-maven-plugin` check, `finalName app`) y `WebhookMockApplication`; verificar con `mvn -q verify` en `webhook-mock/`
- [x] 1.2 `application.yml`: puerto 8090, `spring.threads.virtual.enabled: true`, Jackson `SNAKE_CASE`, `spring.mvc.problemdetails.enabled: true`, logging estructurado JSON igual al del manager; verificar que la app arranca local

## 2. Configuración en caliente

- [x] 2.1 Record `Behavior(status, delay_ms)` con Bean Validation (200–599, 0–60000, obligatorios) y store en memoria (defecto `volatile` + `ConcurrentHashMap` de overrides, resolución por cliente); test JUnit: defecto inicial 200/0, override solo afecta a su cliente, delete vuelve al defecto
- [x] 2.2 `GET /config`, `PUT /config`, `PUT /config/clients/{client_id}`, `DELETE /config/clients/{client_id}` con log INFO por cambio; test `@WebMvcTest`: 200 con el comportamiento guardado, 204 en delete (exista o no), 400 nombrando `status`/`delay_ms` sin modificar la configuración, y el JSON de `GET /config` del spec

## 3. Recepción de webhooks

- [x] 3.1 `POST /webhooks/{client_id}`: deserializa solo `event_id` y `client_id`, aplica la demora y responde el status configurado con cuerpo vacío; log DEBUG por request y ERROR si el `client_id` del cuerpo difiere del path; test `@WebMvcTest` (con demora 0): status por defecto, override 503 de un cliente no afecta a otro, entrega cruzada genera log ERROR (con `OutputCaptureExtension`) y ningún log contiene el `content` enviado

## 4. Seed, Docker y documentación

- [x] 4.1 `notificator/db/04-seed-subscriptions.sql` con las 80 suscripciones (`CLIENT001`..`CLIENT008` × 10 tipos) hacia `http://webhook-mock:8090/webhooks/<client_id>`; verificar con `docker compose down -v && docker compose up -d mariadb` y `SELECT COUNT(*) FROM subscription` = 80
- [x] 4.2 `webhook-mock/Dockerfile` multi-stage igual al del simulator con `EXPOSE 8090`, `.dockerignore`, y servicio `webhook-mock` en `docker-compose.yml` con puerto `127.0.0.1:8090:8090`; verificar con `docker compose up -d webhook-mock` y `curl localhost:8090/config`
- [x] 4.3 README: qué es el mock, ejemplos `curl` para cambiar defecto/cliente/demora y reset, que el seed requiere volumen nuevo y que la API de config es solo local; verificar que los comandos documentados funcionan tal cual

## 5. Verificación integrada

- [x] 5.1 Con el entorno levantado (volumen nuevo) y `--profile simulator`: con el mock por defecto los eventos quedan `DELIVERED`; con `PUT /config/clients/CLIENT002` `{"status":503,"delay_ms":0}` solo los de `CLIENT002` pasan a `RETRYING` sin afectar al resto (el breaker por cliente se verifica cuando se implemente en el notificator); con `delay_ms=15000` en `CLIENT003` sus entregas cortan por timeout; y no aparece ningún log ERROR de entrega cruzada en el mock

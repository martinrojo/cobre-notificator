# Tasks

## 1. Provisioning y reglas de plataforma

- [x] 1.1 `docker-compose.yml`: montar `./observability/grafana/provisioning/alerting` en `/etc/grafana/provisioning/alerting:ro`, junto a los otros montajes de provisioning. Crear `observability/grafana/provisioning/alerting/rules.yml` (`apiVersion: 1`, carpeta `Cobre`) con el grupo `plataforma` y las 6 reglas de la tabla del design (decisiones 2 y 3): query A sobre `prometheus`, threshold C sobre `__expr__`, `noDataState: OK`, `execErrState: Error`, label `severity` y un `summary` en las annotations. Verificar con `docker compose up -d grafana` y `curl -s -u admin:$GRAFANA_ADMIN_PASSWORD localhost:3000/api/v1/provisioning/alert-rules | jq '.[].title'`, que tiene que listar las 6 reglas. En los logs de Grafana no tiene que aparecer ningún error de provisioning.
- [x] 1.2 Con el stack sano y sin simulador, esperar 2 minutos y comprobar en `localhost:3000/api/prometheus/grafana/api/v1/rules` que las 6 reglas están en `inactive`, ninguna en `error` ni en `nodata` como alerta. Correr cada query A en Prometheus (`localhost:9090`) y ver que devuelve el valor esperado o vacío.
- [x] 1.3 Manager: registrar en 0 `manager.replay.requests` para `published`, `in_progress`, `not_allowed`, `not_found` y `unavailable` en el constructor de `NotificationEventReplayService`, con un test que construya el servicio con un `SimpleMeterRegistry` y verifique las 5 series en 0. En `KafkaReplayRequestPublisher`, atrapar también `org.apache.kafka.common.KafkaException` (el producer que no se puede construir), con un test que la lance desde `send` y espere `ReplayPublishException`. Correr `mvn test` en `manager` y reconstruir con `docker compose up -d --build manager`.
- [x] 1.4 Disparar las alertas `critical`, verificar cada una en Alerting y documentar el comando que la dispara:
  - `docker compose stop notificator` → app caída `app=notificator` en ~1 min.
  - Con el simulador corriendo, el mismo stop por más de 5 min → notificator sin consumir.
  - `docker compose stop mariadb` con el simulador corriendo → errores de persistencia en ~2 min.
  - Publicar en `transactions.events.v1` un mensaje que no sea JSON (`kafka-console-producer` dentro del contenedor `kafka`) → DLT por error interno.
  - `docker compose stop kafka` (el host no resuelve) o `docker compose pause kafka` (el broker no responde) y un replay de un evento `FAILED`: en los dos casos tiene que responder 503 y dispararse la alerta de replay sin Kafka. Requiere la 1.3.
  
  Al levantar cada servicio, la alerta correspondiente tiene que volver a normal.

## 2. Reglas de entrega

- [x] 2.1 Agregar a `rules.yml` el grupo `entrega` con las reglas de muchos clientes fallando (query de la decisión 4), recovery cierra eventos y circuito abierto (con `label_replace` a `client_id`). Verificar con el mismo `curl` de 1.1 que hay 9 reglas.
- [x] 2.2 Con el simulador corriendo, configurar el mock con `CLIENT002` en 503. A los ~5 minutos tiene que haber una alerta de circuito abierto con `client_id=CLIENT002` y ninguna para otro cliente, y la de muchos clientes fallando tiene que seguir en `inactive`. Después del `DELETE` del override, la alerta vuelve a normal.
- [x] 2.3 Poner el defecto del mock en 400 (`PUT /config` `{"status":400,"delay_ms":0}`) y verificar que la alerta de muchos clientes fallando se dispara en ~5 minutos. Para forzar la alerta de recovery cierra eventos, dejar un evento `RETRYING` en `attempts=4` con `updated_at` más viejo que el lease (un `UPDATE` en MariaDB) y verificar que el job lo pasa a `FAILED` y que la alerta se dispara.

## 3. Panel en General

- [x] 3.1 `observability/grafana/dashboards/general.json`: agregar arriba de todo un panel `alertlist` (decisión 5) que muestre los estados firing y pending. Verificar que el JSON es válido (`python3 -m json.tool`) y que, con la alerta de `CLIENT002` disparada (2.2), el panel la muestra con `severity=info` y `client_id=CLIENT002`.

## 4. Documentación

- [x] 4.1 README, sección de observabilidad: una tabla con las alertas (nombre, severidad, qué significa), una línea que explique que no hay contact point y que las alertas se ven en Alerting y en `General`, y los comandos de 1.4, 2.2 y 2.3 para dispararlas en local. Verificar que al menos el comando de `CLIENT002` en 503 produce la alerta descrita.

## 5. Verificación integral

- [x] 5.1 Con un volumen de Grafana vacío (`docker compose rm -sfv grafana && docker volume rm <proyecto>_grafana-data && docker compose up -d grafana`), recorrer los escenarios de `specs/operations-alerts/spec.md`. Aunque esté cubierto por las tareas anteriores, confirmar también que un replay con Kafka caído no dispara la alerta de errores del manager y que un mensaje `exhausted` no dispara la de DLT por error interno. Correr `openspec validate add-grafana-alerts --strict`.

## Workflow follow-up

- Archivar `add-grafana-dashboards` antes que este change.

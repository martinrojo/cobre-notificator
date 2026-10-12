# Design

## Context

- Grafana 12.1 ya provisiona datasources (`uid: prometheus`, `uid: loki`) y dashboards desde `observability/grafana/provisioning/`. No hay alerting provisionado, ni Prometheus rules, ni Alertmanager.
- Prometheus scrapea cada 15 s `manager`, `notificator` y las métricas de Kafka de Alloy (`app=kafka`).
- Todas las métricas que usan las alertas ya existen (ver `add-grafana-dashboards`). Los contadores con tags de valores conocidos se registran en 0 al arrancar. Los que llevan `client_id` aparecen recién con el primer evento de ese cliente.
- Los consumer groups de retry y DLT (`notificator-retry-0`..`2`, `notificator-dlt`) tienen lag por diseño mientras los mensajes esperan su backoff.

## Goals / Non-Goals

**Goals:**
- Que todas las reglas vivan en un archivo del repo y se puedan revisar en un PR.
- Que cada alerta se pueda disparar en local con el simulador, el mock de webhooks o `docker compose stop`.

**Non-Goals:**
- Afinar los umbrales para producción. Los valores elegidos son razonables para el volumen del simulador y quedan en un solo archivo.

## Decisions

### 1. Alerting de Grafana provisionado por archivo

Un solo archivo `observability/grafana/provisioning/alerting/rules.yml` (`apiVersion: 1`, `groups:`), montado en `/etc/grafana/provisioning/alerting` como los otros directorios de provisioning. Las reglas provisionadas quedan bloqueadas en la UI. Es el mismo criterio que `allowUiUpdates: false` en los dashboards.

- *Alternativa*: Prometheus rules + Alertmanager. Suma un contenedor y una segunda UI. Además, sin contact points (fuera de alcance), Alertmanager no aporta nada.
- *Alternativa*: crear las reglas a mano en la UI. Se pierden con `docker compose down -v` y no quedan en git.

Las reglas van en dos grupos, en una carpeta de Grafana `Cobre`:

| Grupo | Intervalo | Reglas |
|---|---|---|
| `plataforma` | 30s | app caída, notificator sin consumir, errores de persistencia, DLT por error interno, replay sin Kafka, errores del manager |
| `entrega` | 1m | muchos clientes fallando, eventos cerrados por el job de recuperación, circuito abierto por cliente |

### 2. Forma de cada regla: la query devuelve el valor y Grafana compara

Cada regla tiene una query PromQL instantánea (`refId: A`, datasource `prometheus`) que devuelve una serie por dimensión (`app`, `client_id`) y una expresión `threshold` (`refId: C`, datasource `__expr__`) que la compara. Así, la UI muestra el valor real que disparó la alerta, que es lo que se quiere enseñar en la demo. En los ratios, la guarda de volumen mínimo va en la PromQL para no dividir por 0.

`noDataState: OK` en todas las reglas: sin tráfico no hay series, y eso no es una falla. `execErrState: Error`: si Prometheus no responde, la regla queda en error, y eso se ve en la UI.

- *Alternativa*: comparación dentro de la PromQL (`up == 0`) y threshold `> 0`. Funciona igual, pero la alerta muestra `1` en lugar del valor que la disparó.

### 3. Queries

| Alerta | Severidad | Query (A) | Umbral (C) | `for` |
|---|---|---|---|---|
| App caída | critical | `up{app=~"manager\|notificator\|kafka"}` | `< 1` | 1m |
| Notificator sin consumir | critical | `sum(kafka_consumergroup_uncommitted_offsets{consumergroup="notificator"} >= 0) and sum(delta((kafka_consumergroup_uncommitted_offsets{consumergroup="notificator"} >= 0)[5m:30s])) > 0` | `> 10000` | 5m |
| Errores de persistencia | critical | `sum(rate(notificator_persistence_errors_total[2m]))` | `> 0` | 2m |
| DLT por error interno | critical | `sum(increase(notificator_dlt_messages_total{reason=~"invalid\|unexpected"}[10m]))` | `> 0` | 0s |
| Replay sin Kafka | critical | `sum(increase(manager_replay_requests_total{outcome="unavailable"}[5m]))` | `> 0` | 0s |
| Errores del manager | warning | `sum(rate(http_server_requests_seconds_count{app="manager", status=~"5..", status!="503"}[5m])) / (sum(rate(http_server_requests_seconds_count{app="manager"}[5m])) > 0)` | `> 0.05` | 5m |
| Muchos clientes fallando | warning | ver abajo | `> 0.3` | 5m |
| Recovery cierra eventos | warning | `sum(increase(notificator_recovery_events_total{action="failed"}[15m]))` | `> 0` | 0s |
| Circuito abierto | info | `max by (client_id) (label_replace(resilience4j_circuitbreaker_state{state=~"open\|half_open"}, "client_id", "$1", "name", "(.*)"))` | `> 0` | 5m |

**Notificator sin consumir**: hace falta el umbral más el crecimiento, porque un lag alto que baja es un notificator poniéndose al día, no uno trabado. El `>= 0` descarta el `-1` que publica el exporter cuando no conoce el offset; en el `delta` va dentro de un subquery (`[5m:30s]`) porque un rango no se puede aplicar a una comparación. El umbral de 10000 mensajes deja margen para picos de carga y para un notificator que se pone al día después de un reinicio.

**Errores del manager**: el único 503 que produce el manager es el del replay sin Kafka, que ya tiene su propia alerta. Por eso se excluye `status="503"` y no la URI: un 500 en el replay sí cuenta.

**Circuito abierto**: el breaker se llama como el `client_id`. El `label_replace` expone ese valor como label `client_id`, así cada cliente es una instancia de alerta separada. `half_open` cuenta como abierto, porque con un webhook caído el circuito alterna entre los dos estados cada `CIRCUIT_OPEN_WAIT` (30 s), y con `open` solo la alerta se reiniciaría en cada prueba.

### 4. Muchos clientes fallando: sobre intentos, no sobre estados finales

```
count(
  sum by (client_id) (increase(notificator_webhook_attempts_total{outcome!="delivered"}[10m]))
  / sum by (client_id) (increase(notificator_webhook_attempts_total[10m])) > 0.5
)
/
count(sum by (client_id) (increase(notificator_webhook_attempts_total[10m])) > 0)
```

Cuenta qué fracción de los clientes activos tiene la mayoría de sus intentos sin entregar. Si el primer `count` no tiene series (ningún cliente fallando), la query no devuelve nada y `noDataState: OK` la deja en normal.

- *Alternativa*: tasa de éxito global (`delivered / (delivered + failed)`). Un cliente grande caído la hunde, y ese caso ya lo cubre la alerta de circuito abierto. La alerta tiene que detectar una falla que afecta a todos, como un payload que todos rechazan o TLS roto.
- *Alternativa*: fracción de clientes calculada sobre `notificator_deliveries_total`. Un 5xx o un timeout recién llega a `FAILED` después de los 4 intentos (~31 min con `RETRY_DELAY=60s`). Con los intentos, la alerta reacciona en minutos. Los rechazos por circuito abierto (`status="circuit_open"`) cuentan como no entregados: son consecuencia de que el webhook falla.

### 5. Panel de alertas en General

Un panel nativo `alertlist` arriba de todo en `General`, sin filtro de carpeta (el uid de la carpeta `Cobre` lo genera Grafana, y en esta instancia no hay otras alertas), que muestra las alertas en estado firing y pending con sus labels. No necesita datasource ni plugins.

## Risks / Trade-offs

- [El umbral de lag y el 30% son arbitrarios] → Están en un solo archivo y el design explica de dónde salen. Afinarlos en producción es fuera de alcance.
- [Sin contact point, nadie recibe la alerta fuera de la UI] → Aceptado en el proposal. Para notificar basta con agregar un contact point y una policy, sin tocar las reglas.
- [Reglas bloqueadas en la UI: un ajuste rápido en la demo requiere editar el archivo y reiniciar Grafana] → Es el mismo trade-off que ya se aceptó con los dashboards.
- [`delta` sobre un gauge con reinicios del exporter puede dar un salto] → Con `for: 5m`, un salto aislado no dispara la alerta.
- [Muchos clientes fallando con pocos clientes activos: con 2 clientes, uno caído ya es el 50%] → Con el simulador hay 8 clientes activos. Si hiciera falta, se agrega a la PromQL un mínimo de clientes activos.

## Migration Plan

Se aplica con `docker compose up -d grafana`, que recrea el contenedor con el volumen nuevo. Para hacer rollback, se saca el volumen y el archivo de reglas, y Grafana borra las reglas provisionadas al reiniciar.

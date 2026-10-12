# Tasks

## 1. Heap fijo

- [x] 1.1 Cambiar el `ENTRYPOINT` de `manager/Dockerfile` y `notificator/Dockerfile` a `["java", "-Xms2g", "-Xmx2g", "-jar", "/app/app.jar"]`. Verificar con `docker compose up -d --build manager notificator` y luego `curl -s --get localhost:9090/api/v1/query --data-urlencode 'query=sum by (app)(jvm_memory_max_bytes{area="heap"} > 0)'`: debe dar 2147483648 para las dos apps (esperar un scrape de 15 s). La misma consulta con `jvm_memory_committed_bytes` debe dar el mismo valor.
- [x] 1.2 Verificar que el arranque sigue escribiendo solo JSON: `docker compose logs --no-log-prefix manager notificator | grep -vc '^{'` debe devolver 0.

## 2. Dashboard JVM

- [x] 2.1 Crear `observability/grafana/dashboards/jvm.json` con uid `jvm`, título `JVM`, tag `cobre`, `editable: false`, `refresh: 30s`, `now-1h`, `schemaVersion: 41` y la variable `app` (decisión 2 del design). Verificar que `jq . jvm.json` no da error.
- [x] 2.2 Agregar las filas Resumen, Memoria, GC, Threads y Recursos con las consultas de la tabla de la decisión 3, todas filtradas por `{app="$app"}`. Verificar cada consulta contra Prometheus (`/api/v1/query`) con `app="manager"` y con `app="notificator"`: tiene que devolver series, salvo los timeouts de Hikari, que pueden estar vacíos.
- [x] 2.3 Recargar con `docker compose restart grafana` y verificar en `http://localhost:3000`:
  - `JVM` aparece en la búsqueda por el tag `cobre`;
  - con `app=manager`, el panel de executors muestra solo `applicationTaskExecutor`;
  - con `app=notificator`, muestra también `taskScheduler`;
  - ningún panel queda en "No data", salvo los timeouts del pool.
- [x] 2.4 Verificar el escenario del pool agotado. Con el simulador corriendo (`docker compose --profile simulator up -d`), detener MariaDB con `docker compose stop mariadb`. El panel del pool del notificator debe mostrar conexiones pendientes o timeouts. Con `docker compose start mariadb` deben volver a 0.
- [x] 2.5 Agregar **JVM** a la lista de dashboards del `README.md`, con una nota sobre el heap fijo de 2 GB por app y la RAM que requiere el stack. Verificar que la lista nombra los 6 dashboards con tag `cobre`.

## Workflow follow-up

- Archivar `add-grafana-dashboards` antes de archivar este change: el delta de `operations-dashboards` depende de él.

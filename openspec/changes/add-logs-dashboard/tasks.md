# Tasks

## 1. Dashboard

- [x] 1.1 Crear `observability/grafana/dashboards/logs.json` (`uid: logs`, `title: Logs`) con las variables `app` (Servicio), `level` (Nivel) y `regex` (Regex, textbox vacío) y el panel de logs del design, con el `line_format` del panel de `apps.json` sin cambios; verificar con `python3 -m json.tool` que el JSON es válido
- [x] 1.2 Quitar de `apps.json` el panel `Logs` y la variable `level`; verificar con `python3 -m json.tool` que es válido, que `grep -c 'loki' observability/grafana/dashboards/apps.json` da 0 y que el resto de los paneles y la variable `client_id` siguen iguales (`git diff` solo muestra borrados)
- [x] 1.3 Con el volumen de Grafana vacío (`docker compose rm -sfv grafana && docker volume rm <proyecto>_grafana-data && docker compose up -d grafana`) y `--profile simulator`: `curl -u admin:$GRAFANA_ADMIN_PASSWORD 'localhost:3000/api/search?query=Logs'` devuelve el dashboard `logs`, el dashboard `Apps` abre sin panel de logs ni selector `level`, y en la UI el selector `Servicio` ofrece `All`, `manager` y `notificator` (sin `gateway`) y `Nivel` los niveles presentes
- [ ] 1.4 Verificar la consulta del panel contra Loki vía Grafana (`/api/ds/query` con el datasource `loki`) para los escenarios del spec: regex vacía devuelve todas las líneas; `client_id=CLIENT001\b` solo devuelve líneas de `CLIENT001`; `(?i)event delivered|replay requested` devuelve ambos mensajes; `Servicio=notificator` + `Nivel=ERROR` solo `ERROR` del notificator; una regex con `JsonParseException` (tras enviar un mensaje inválido a `transactions.events.v1`) devuelve la línea con `exception=...`; `(` devuelve error de parseo; y `/loki/api/v1/labels` sigue sin `client_id` ni `event_id`

## 2. Documentación

- [ ] 2.1 README, sección de observabilidad: agregar el dashboard **Logs** a la fila de Grafana, reemplazar "El panel de logs del dashboard ya los muestra así" por el dashboard **Logs**, y agregar un párrafo con los filtros (Servicio, Nivel, Regex sobre la línea legible, RE2, `(?i)` para ignorar mayúsculas, sin backticks) y un ejemplo `client_id=CLIENT001\b`; verificar que el ejemplo documentado funciona tal cual en el dashboard

## Workflow follow-up

- Archivar `add-observability-stack` antes que este change: el delta MODIFIED de `observability-stack` necesita que su spec ya exista en `openspec/specs/`.

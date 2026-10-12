# Design

## Context

Ver proposal.md (Why). Lo que ya existe y se reutiliza tal cual:

- Alloy manda a Loki los logs de `manager|notificator|gateway` con los labels `app` y `level` (`observability/alloy/config.alloy`).
- Datasource Loki con `uid: loki` y provider de dashboards que carga todo `observability/grafana/dashboards/` (`allowUiUpdates: false`).
- El panel `Logs` de `apps.json` (que se mueve a este dashboard) ya tiene el `line_format` legible (nivel, logger simple, mensaje, `event_id`, `client_id`, `error`, `exception` con saltos escapados) y una variable `level` de Loki.

## Goals / Non-Goals

**Goals:**
- Un archivo `logs.json` nuevo y `apps.json` sin logs, sin tocar configuración de Grafana, Loki ni Alloy.
- Un solo lugar con la consulta de logs: el `line_format` se mueve, no se duplica.

**Non-Goals:**
- Links entre `Apps` y `Logs` (se llega por la búsqueda de dashboards o el tag `cobre`).

## Decisions

### Consulta

```logql
{app=~"$app", level=~"$level"}
  | json
  | line_format `<template movido desde apps.json, sin cambios>`
  |~ `$regex`
```

- **Variables**: `app` y `level` de tipo `query` sobre Loki (`label_values(app)`, `label_values(level)`), `multi`, `includeAll`, `allValue: ".*"`, `refresh: 2` (al cambiar el rango de tiempo). Labels visibles `Servicio` y `Nivel`. `regex` de tipo `textbox`, label `Regex`, valor por defecto vacío.
- **La regex va después de `line_format`**: filtra sobre la línea legible, así `client_id=CLIENT001` o `exception=.*Timeout` funcionan igual que como se leen. Alternativa descartada: `|~` antes de `| json` (más barato, Loki no parsea las líneas descartadas), pero obliga a escribir la regex contra el JSON crudo (`"client_id":"CLIENT001"`) y no matchea el logger simple. Con el volumen local el costo no importa.
- **Regex vacía**: `|~ ``` matchea todo, no hace falta lógica condicional.
- **String con backticks** (`` `$regex` ``): raw string en LogQL, la regex llega a RE2 sin re-escapar `\`. Con comillas dobles el usuario tendría que escribir `\\b` en vez de `\b`.
- **`allValue: ".+"`** en vez del default de Grafana (lista de valores unidos con `|`): sigue funcionando para servicios o niveles que aparezcan después de cargar el dashboard. No `.*`: Loki rechaza un selector sin ningún matcher que excluya el vacío (`app=~".*", level=~".*"`), y toda línea tiene `app` y `level`.
- **Panel**: tipo `logs`, ancho y alto completos, `showTime`, `wrapLogMessage`, `sortOrder: Descending` (las opciones del panel actual de `Apps`). Dashboard `uid: logs`, `title: Logs`, `refresh: 30s`, `now-1h`, `editable: false`, tag `cobre`.

### `apps.json` sin logs

Se borran el panel `Logs` (último, `gridPos.y: 28`, no deja huecos) y la variable `level`, que solo usaba ese panel. La variable `client_id` y los paneles de métricas no cambian.

## Risks / Trade-offs

- [Un backtick en la regex rompe el raw string y la consulta falla] → el panel muestra el error de parseo; en una regex sobre estos logs no hace falta un backtick. Se documenta en el README.
- [Regex inválida] → Loki responde 400 y Grafana muestra el error en el panel; sin manejo extra.
- [Quien estaba acostumbrado a ver logs en `Apps` no los encuentra] → el README apunta al dashboard `Logs`; ambos tienen el tag `cobre`.
- [Grafana es acceso de operador: ve logs de todos los clientes — OWASP A01 Broken Access Control] → no cambia respecto de hoy: Grafana escucha solo en `127.0.0.1`, anónimo deshabilitado y nunca se expone a clientes. Los logs no incluyen `content` (A09), así que el dashboard no expone datos financieros.

## Migration Plan

Agregar el archivo y reiniciar Grafana (`docker compose restart grafana`); el provider lo levanta solo. Rollback: borrar `logs.json` y restaurar `apps.json` desde git.

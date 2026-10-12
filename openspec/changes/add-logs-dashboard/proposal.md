# Proposal

## Why

Hoy los logs solo se ven en el panel "Logs" al pie del dashboard `Apps`, que mezcla todas las apps, solo filtra por nivel y comparte pantalla con las métricas. Para investigar una entrega (por `event_id`, `client_id`, un error puntual) hace falta un dashboard dedicado donde elegir el servicio, el nivel y filtrar el texto con una regex.

## What Changes

- Nuevo dashboard de Grafana `Logs`, provisionado como código junto a `apps.json`, con un único panel de logs a pantalla completa.
- Variable **Servicio** (`app`): selección múltiple con opción "All", valores leídos del label `app` de Loki.
- Variable **Nivel** (`level`): selección múltiple con opción "All", valores leídos del label `level` de Loki.
- Variable **Regex**: caja de texto libre; vacía no filtra. Se aplica sobre la línea formateada que ve el usuario (el mismo formato legible del panel de logs de `Apps`), así que sirve para buscar por mensaje, logger, `event_id`, `client_id` o texto del stack trace.
- Se saca del dashboard `Apps` el panel `Logs` y la variable `level` (solo la usaba ese panel). `Logs` pasa a ser el único lugar de Grafana para ver logs; `Apps` queda solo con métricas.
- Sin cambios en Alloy, en Loki ni en las aplicaciones.

Aplicaciones tocadas: ninguna (gateway, manager y notificator no cambian). Solo cambian los dashboards en `observability/grafana/dashboards/`.

### Fuera de alcance

- Nuevos labels en Loki (en particular `client_id` y `event_id` siguen siendo campos del JSON, nunca labels).
- Filtros dedicados por `client_id` o `event_id` (se cubren con la regex).
- Panel de volumen de logs por nivel, alertas sobre logs, retención de Loki.
- Logs de infraestructura (Kafka, MariaDB, el stack de observabilidad): Alloy sigue recolectando solo `manager|notificator|gateway`.
- Control de acceso por cliente en Grafana: es una herramienta interna de operación, no expuesta a clientes.

## Capabilities

### New Capabilities

- `logs-dashboard`: dashboard de Grafana para explorar los logs de las apps filtrando por servicio, nivel y regex.

### Modified Capabilities

- `observability-stack`: el dashboard `Apps` deja de tener panel de logs; la lectura legible de logs en Grafana pasa al dashboard `Logs`.

## Impact

- Archivo nuevo: `observability/grafana/dashboards/logs.json`. El provider existente (`/var/lib/grafana/dashboards`) lo carga sin cambios de configuración.
- `observability/grafana/dashboards/apps.json`: se quitan el panel `Logs` y la variable `level`.
- README: el dashboard `Logs` en la sección de observabilidad, en lugar del panel de logs de `Apps`.
- Depende del change `add-observability-stack` (Loki, Alloy, Grafana y los labels `app`/`level`), que debe archivarse antes que este: el delta de `observability-stack` modifica requisitos que ese change agrega.
- Sin dependencias nuevas.

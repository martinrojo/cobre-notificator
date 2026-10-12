# Spec Delta

## Purpose

Dashboard de Grafana para explorar los logs de las apps, eligiendo servicio y nivel y filtrando el texto de cada línea con una expresión regular.

## ADDED Requirements

### Requirement: Dashboard Logs provisionado
Grafana SHALL arrancar con un dashboard titulado `Logs`, cargado desde un archivo del repo sin configuración manual, con un panel que muestra los logs de Loki en el formato legible de una línea por evento (nivel, logger, mensaje, `event_id`, `client_id`, `error`, `exception`), ordenados del más reciente al más antiguo.

#### Scenario: Dashboard disponible al arrancar
- **GIVEN** un volumen de Grafana vacío y el stack levantado con `docker compose up -d`
- **WHEN** se buscan dashboards en Grafana
- **THEN** aparece `Logs` junto a `Apps`, y al abrirlo muestra los logs de las apps sin editar nada

#### Scenario: Línea legible
- **GIVEN** el notificator entrega un evento `EVT1` de `CLIENT001` y lo deja en `DELIVERED`
- **WHEN** se abre el dashboard `Logs` con todos los filtros en su valor por defecto
- **THEN** aparece la línea `INFO DeliveryService Event delivered event_id=EVT1 client_id=CLIENT001`

### Requirement: Filtro por servicio
El dashboard SHALL tener un selector `Servicio` de selección múltiple, con opción `All` por defecto, cuyas opciones son los valores del label `app` presentes en Loki. Solo se muestran las líneas de los servicios elegidos.

#### Scenario: Un solo servicio
- **GIVEN** logs de `manager` y `notificator` en el rango de tiempo
- **WHEN** se elige solo `manager` en `Servicio`
- **THEN** el panel muestra solo líneas con `app=manager`

#### Scenario: Todos los servicios
- **GIVEN** logs de `manager` y `notificator` en el rango de tiempo
- **WHEN** `Servicio` queda en `All`
- **THEN** el panel muestra líneas de ambos servicios

#### Scenario: Servicio sin logs no se ofrece
- **GIVEN** el `gateway` todavía no existe y no hay logs con `app=gateway`
- **WHEN** se abre el selector `Servicio`
- **THEN** las opciones son `All`, `manager` y `notificator`

### Requirement: Filtro por nivel
El dashboard SHALL tener un selector `Nivel` de selección múltiple, con opción `All` por defecto, cuyas opciones son los valores del label `level` presentes en Loki. Solo se muestran las líneas de los niveles elegidos.

#### Scenario: Solo errores
- **GIVEN** logs `INFO` de un evento `DELIVERED` y logs `ERROR` de un evento enviado al DLT
- **WHEN** se elige solo `ERROR` en `Nivel`
- **THEN** el panel muestra solo líneas que empiezan con `ERROR`, incluida la del DLT con su `exception=...`

#### Scenario: Servicio y nivel combinados
- **GIVEN** logs `ERROR` del `manager` y del `notificator`
- **WHEN** se elige `Servicio` = `notificator` y `Nivel` = `ERROR`
- **THEN** el panel muestra solo los `ERROR` del `notificator`

### Requirement: Filtro por regex
El dashboard SHALL tener un campo de texto `Regex`, vacío por defecto. Vacío no filtra; con un valor, solo se muestran las líneas cuya versión legible (la que ve el usuario) contiene una coincidencia con esa expresión regular (sintaxis RE2, sensible a mayúsculas salvo `(?i)`). Se combina con los filtros de servicio y nivel.

#### Scenario: Regex vacía
- **GIVEN** el campo `Regex` vacío
- **WHEN** se mira el panel
- **THEN** se muestran todas las líneas que cumplen servicio y nivel

#### Scenario: Filtrar por cliente
- **GIVEN** logs de entregas de `CLIENT001` y `CLIENT002`
- **WHEN** se escribe `client_id=CLIENT001\b` en `Regex`
- **THEN** el panel muestra solo líneas de `CLIENT001` y ninguna de `CLIENT002`

#### Scenario: Alternativa e insensible a mayúsculas
- **GIVEN** logs con los mensajes `Event delivered` y `Replay requested`
- **WHEN** se escribe `(?i)event delivered|replay requested` en `Regex`
- **THEN** el panel muestra las líneas de ambos mensajes y ninguna otra

#### Scenario: Buscar en el stack trace
- **GIVEN** un `ERROR` del notificator con una excepción `JsonParseException` adjunta
- **WHEN** se escribe `JsonParseException` en `Regex`
- **THEN** aparece la línea de ese error con su `exception=...`

#### Scenario: Regex inválida
- **GIVEN** el panel funcionando
- **WHEN** se escribe `(` en `Regex`
- **THEN** el panel muestra el error de parseo de la consulta en lugar de líneas, y al corregir la regex vuelve a mostrar resultados sin recargar el dashboard

### Requirement: Sin labels por cliente
El dashboard SHALL filtrar `client_id` y `event_id` solo como texto de la línea, sin requerir que sean labels de Loki.

#### Scenario: Labels de Loki sin cambios
- **GIVEN** el dashboard `Logs` en uso con filtros por `client_id`
- **WHEN** se listan los labels de Loki (`/loki/api/v1/labels`)
- **THEN** no existen `client_id` ni `event_id`

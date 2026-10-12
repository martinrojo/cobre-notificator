# Spec Delta

## MODIFIED Requirements

### Requirement: Dashboard Logs provisionado
Grafana SHALL arrancar con un dashboard titulado `Logs`, cargado desde un archivo del repo sin configuración manual, con un panel que muestra los logs de Loki en el formato legible de una línea por evento (nivel, logger, mensaje, `event_id`, `client_id`, `error`, `exception`), ordenados del más reciente al más antiguo.

#### Scenario: Dashboard disponible al arrancar
- **GIVEN** un volumen de Grafana vacío y el stack levantado con `docker compose up -d`
- **WHEN** se buscan dashboards en Grafana
- **THEN** aparece `Logs` junto a `General`, `Notificator`, `Cliente` y `Kafka`, y al abrirlo muestra los logs de las apps sin editar nada

#### Scenario: Línea legible
- **GIVEN** el notificator entrega un evento `EVT1` de `CLIENT001` y lo deja en `DELIVERED`
- **WHEN** se abre el dashboard `Logs` con todos los filtros en su valor por defecto
- **THEN** aparece la línea `INFO DeliveryService Event delivered event_id=EVT1 client_id=CLIENT001`

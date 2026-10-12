# Spec Delta

## ADDED Requirements

### Requirement: Dashboard JVM
Grafana SHALL arrancar con un dashboard `JVM` provisionado desde el repo, con el tag `cobre` y no editable. Arriba SHALL tener una variable `app` de selección única con `manager` y `notificator`, y todos sus paneles SHALL filtrar por esa app.

#### Scenario: Dashboard disponible al arrancar
- **GIVEN** un volumen de Grafana vacío y el stack levantado con `docker compose up -d`
- **WHEN** se buscan dashboards con el tag `cobre`
- **THEN** aparece `JVM`, junto a `General`, `Notificator`, `Cliente`, `Kafka` y `Logs`

#### Scenario: Aislamiento por app
- **GIVEN** `manager` y `notificator` corriendo, con el simulador publicando en `transactions.events.v1`
- **WHEN** se elige `manager` en `$app`
- **THEN** ningún panel muestra series de `notificator`: los executors listan solo `applicationTaskExecutor` y no `taskScheduler`, y las pausas de GC son solo las del manager

#### Scenario: Sin datos por cliente
- **GIVEN** entregas de `CLIENT001` y `CLIENT002` en `DELIVERED` y `FAILED`
- **WHEN** se mira cualquier panel del dashboard `JVM`
- **THEN** ninguna serie tiene label `client_id`: las métricas de la JVM son por proceso, no por cliente

### Requirement: Paneles del dashboard JVM
El dashboard `JVM` SHALL mostrar, para la app elegida, estos paneles:
- uptime, versión de la JVM y CPU del proceso;
- heap usado contra el máximo, y heap después del GC;
- non-heap por pool;
- pausas de GC por causa y overhead de GC;
- threads por estado, clases cargadas y archivos abiertos;
- conexiones del pool de la base;
- executors.

#### Scenario: Paneles con datos
- **GIVEN** `manager` y `notificator` corriendo
- **WHEN** se elige cualquiera de las dos en `$app`
- **THEN** todos los paneles muestran datos, salvo los timeouts del pool, que pueden estar vacíos, y las pausas de GC, que aparecen con el primer GC después de arrancar

#### Scenario: Pool de conexiones agotado
- **GIVEN** el notificator procesando eventos y MariaDB detenida
- **WHEN** se elige `notificator` en `$app`
- **THEN** el panel del pool de la base muestra conexiones pendientes o timeouts de conexión mientras la base está caída, y vuelven a 0 cuando se levanta

### Requirement: Heap fijo de las apps
`manager` y `notificator` SHALL arrancar con el heap mínimo y el heap máximo fijos en 2 GiB, sin depender de la memoria del host. El arranque SHALL no escribir en la salida de las apps líneas que no sean JSON.

#### Scenario: Heap máximo en el dashboard
- **GIVEN** el stack levantado con `docker compose up -d` en un host con cualquier cantidad de RAM mayor a 6 GiB
- **WHEN** se elige `manager` o `notificator` en `$app` del dashboard `JVM`
- **THEN** el heap máximo es 2 GiB y el heap committed también es 2 GiB desde el arranque

#### Scenario: Logs del arranque siguen en JSON
- **GIVEN** el notificator recién recreado con `docker compose up -d --force-recreate notificator`
- **WHEN** se leen sus logs con `docker compose logs notificator`
- **THEN** todas las líneas son JSON, sin un aviso `Picked up JAVA_TOOL_OPTIONS`

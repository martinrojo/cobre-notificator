# Spec Delta

## ADDED Requirements

### Requirement: Métricas de Kafka del lado del broker
Prometheus SHALL tener, consultando al broker y sin depender de que los consumidores estén vivos, el lag por consumer group, tópico y partición y el offset más reciente de cada partición de los tópicos del flujo. No SHALL agregarse un contenedor al compose para obtenerlas, ni publicarse puertos nuevos en el host.

#### Scenario: Lag con el consumidor detenido
- **GIVEN** el stack levantado, el notificator detenido y 100 eventos publicados en `transactions.events.v1`
- **WHEN** se consulta en Prometheus `sum(kafka_consumergroup_uncommitted_offsets{consumergroup="notificator",topic="transactions.events.v1"})`
- **THEN** el valor es mayor o igual a 100, separado en series para las particiones 0, 1 y 2

#### Scenario: Offsets de los tópicos de retry y DLT
- **GIVEN** el stack levantado
- **WHEN** se consulta `kafka_topic_partition_current_offset` en Prometheus
- **THEN** existen series para `notificator.retry.v1-0`, `notificator.retry.v1-1`, `notificator.retry.v1-2` y `notificator.dlt.v1`

#### Scenario: Sin puertos nuevos
- **GIVEN** el stack levantado
- **WHEN** se listan los puertos publicados con `docker compose ps`
- **THEN** no aparece ningún puerto nuevo de Alloy ni de un exporter de Kafka

## MODIFIED Requirements

### Requirement: Grafana provisionado como código
Grafana SHALL arrancar con los datasources Prometheus y Loki y con los dashboards `General`, `Notificator`, `Cliente`, `Kafka` (métricas) y `Logs` cargados desde archivos del repo. La contraseña del admin SHALL venir de una variable de entorno, nunca del repo.

#### Scenario: Dashboard disponible al arrancar
- **GIVEN** un volumen de Grafana vacío
- **WHEN** se levanta el stack y se abre el dashboard provisionado `General`
- **THEN** muestra, sin configuración manual, la tasa de éxito, las entregas por minuto, los mensajes en DLT, la latencia p95 del webhook y de punta a punta, el lag del notificator y los eventos recuperados, y en su fila "Plataforma" los targets arriba, los replays del manager y los requests HTTP por status

#### Scenario: Latencia p95 del webhook
- **GIVEN** entregas al webhook con resultado `DELIVERED` y `RETRYING`
- **WHEN** se mira el panel de latencia del webhook
- **THEN** muestra el p95 calculado del histograma `notificator_webhook_latency_seconds_bucket`

#### Scenario: Credenciales de admin
- **GIVEN** `GRAFANA_ADMIN_PASSWORD` definido en `.env`
- **WHEN** se ingresa a Grafana con `admin` y esa contraseña
- **THEN** el login es exitoso, y el valor no aparece en ningún archivo commiteado salvo como ejemplo en `.env.example`

# Spec Delta

## Purpose

Permite verificar de forma mínima que el manager está levantado y atendiendo requests HTTP, sin depender de base de datos ni de Kafka.

## ADDED Requirements

### Requirement: Ping del manager
El manager SHALL responder `GET /ping` con HTTP 200 y el cuerpo de texto plano `pong`. El endpoint no requiere `X-Client-Id` ni depende de servicios externos (DB, Kafka), por lo que no expone datos de ningún cliente.

#### Scenario: Ping exitoso
- **GIVEN** el manager está levantado
- **WHEN** se hace `GET /ping`
- **THEN** la respuesta es HTTP 200 con cuerpo `pong`

#### Scenario: Ping sin X-Client-Id
- **GIVEN** el manager está levantado
- **WHEN** se hace `GET /ping` sin el header `X-Client-Id`
- **THEN** la respuesta es HTTP 200 con cuerpo `pong` y no incluye información de ningún cliente

#### Scenario: Ping dentro del contenedor
- **GIVEN** el servicio `manager` levantado con `docker compose up`
- **WHEN** se hace `GET http://localhost:9290/ping`
- **THEN** la respuesta es HTTP 200 con cuerpo `pong`

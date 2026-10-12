# client-identification Specification

## Purpose

Identifica al cliente que hace cada request a la API self-service del manager, para que toda operación quede acotada a sus datos y los requests sin un cliente identificado se rechacen.

## Requirements

### Requirement: Identificación del cliente por header
Todo request al manager, salvo los dirigidos a rutas públicas, SHALL identificar al cliente por el header `X-Client-Id`. Ese client_id es el único que se usa para acotar la operación; ningún parámetro del request puede reemplazarlo.

#### Scenario: Header presente
- **GIVEN** existen eventos de `CLIENT001` y de `CLIENT002`
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 y contiene solo eventos de `CLIENT001`

### Requirement: Rechazo de requests sin cliente
Un request a una ruta no pública SHALL responder HTTP 401 si el header `X-Client-Id` falta, está vacío o contiene solo espacios, antes de cualquier otro procesamiento: no se valida ningún parámetro ni se resuelve la ruta.

#### Scenario: Header ausente
- **WHEN** se hace `GET /notification_events` sin el header `X-Client-Id`
- **THEN** la respuesta es HTTP 401 con un mensaje que indica que falta la identificación del cliente

#### Scenario: Header vacío
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: "   "`
- **THEN** la respuesta es HTTP 401

#### Scenario: 401 antes que 400
- **WHEN** se hace `GET /notification_events?delivery_status=COMPLETED` sin el header `X-Client-Id`
- **THEN** la respuesta es HTTP 401, no 400

#### Scenario: Ruta inexistente sin cliente
- **WHEN** se hace `GET /does-not-exist` sin el header `X-Client-Id`
- **THEN** la respuesta es HTTP 401, no 404

### Requirement: Rutas públicas
Solo las rutas públicas explícitamente listadas SHALL responder sin requerir `X-Client-Id`. Hoy no hay ninguna; cualquier ruta nueva queda protegida por defecto. Los endpoints de actuator se sirven en el puerto de management, que no se publica, y no pasan por esta identificación.

#### Scenario: Ping eliminado
- **WHEN** se hace `GET /ping` sin el header `X-Client-Id`
- **THEN** la respuesta es HTTP 401

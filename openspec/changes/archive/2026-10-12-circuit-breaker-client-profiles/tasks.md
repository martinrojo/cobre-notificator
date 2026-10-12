# Tasks

## 1. Config del circuito

- [x] 1.1 En `notificator/src/main/resources/application.yml`, cambiar `configs.default` a `sliding-window-type: TIME_BASED`, `sliding-window-size: 60` y `minimum-number-of-calls: 10`, con un comentario sobre el bloqueo máximo antes de abrir (decisión 1). Verificar ajustando `clientBreakersUseTheDefaultConfig` en `NotificatorApplicationTest` a `TIME_BASED`, 60 y 10.
- [x] 1.2 Agregar `configs.bajo-volumen` con `base-config: default`, `sliding-window-size: 300` y `minimum-number-of-calls: 5`. Agregar un comentario que diga dos cosas: los perfiles solo cambian la ventana y el mínimo, y la asignación va en `instances.<client_id>.base-config` desde el entorno (decisiones 2 y 3).
- [x] 1.3 Agregar a `NotificatorApplicationTest` la propiedad `resilience4j.circuitbreaker.instances.CLIENT008.base-config=bajo-volumen` y un test que verifique cuatro cosas:
  - `CLIENT008` tiene ventana 300 y mínimo 5;
  - hereda el tipo `TIME_BASED`, el umbral, la espera, las llamadas de prueba y el `max-wait` del default;
  - la espera sigue siendo menor que `retry.delay`;
  - otro cliente (`CLIENT001`) sigue con ventana 60 y mínimo 10.
- [x] 1.4 Verificar con un test unitario los escenarios de ventana por tiempo, sobre un `CircuitBreaker` con ventana `TIME_BASED` corta y la clasificación real (`onError`/`onSuccess`):
  - con menos llamadas que el mínimo no abre;
  - fallos que salieron de la ventana no cuentan;
  - 20 fallos sobre 600 éxitos en la ventana no abren.

  Si Resilience4j no permite inyectar el reloj, usar una ventana de 1-2 s con espera real y dejarlo dicho en el test.

## 2. Asignación en el entorno local

- [x] 2.1 En `docker-compose.yml`, agregar al notificator `SPRING_APPLICATION_JSON` con `instances.CLIENT008.base-config: bajo-volumen`, y un comentario de por qué no se usa una variable de entorno común (decisión 3). Verificar con `docker compose config`, y después de `docker compose up -d --build notificator`, con el log de arranque sin errores y con `resilience4j_circuitbreaker_state{name="CLIENT008",state="closed"} 1` en `/actuator/prometheus` antes de la primera llamada.
- [x] 2.2 Verificación en vivo:
  - con el mock respondiendo 503 a `CLIENT002`, el circuito abre en segundos;
  - el log `Circuit state changed` aparece para `CLIENT008` cuando el mock le responde 503;
  - al restaurar el mock, los dos circuitos se cierran en la siguiente prueba.

## 3. Documentación e integración

- [x] 3.1 Actualizar el párrafo "Circuit breaker por cliente" del README: ventana de 60 s con mínimo 10, perfiles, `bajo-volumen` como ejemplo, cómo asignar un cliente con `SPRING_APPLICATION_JSON` y el aviso de que un `client_id` mal escrito no da error.
- [x] 3.2 Actualizar la línea del circuit breaker en `openspec/config.yaml` si menciona la ventana.
- [x] 3.3 Correr `mvn verify` en `notificator/` (tests y fmt check) y `openspec validate circuit-breaker-client-profiles --strict`.

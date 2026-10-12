# Design

## Context

El cambio parte de lo que dejó `webhook-circuit-breaker`:

- **Cómo se obtiene el breaker.** Hay un breaker de Resilience4j por `client_id`. `RestClientWebhookClient` lo obtiene con `circuitBreakers.circuitBreaker(clientId)`, y todos usan `resilience4j.circuitbreaker.configs.default`.
- **Config actual del default.**
  - Ventana `COUNT_BASED` de 20 llamadas, mínimo 5, umbral 50%.
  - Espera en abierto de 30 s (menor que `retry.delay`, 60 s), 2 llamadas de prueba y `max-wait` de 10 s en half-open.
- **Volumen en local.** En el simulador todos los clientes hacen unos 375 intentos/min. Con la ventana de 20 llamadas, eso son unos 3 s.
- **Un solo hilo por partición.** Cada partición se consume con un único hilo que comparten varios clientes. Antes de abrir, un cliente que hace timeout en todo ocupa ese hilo `minimum-number-of-calls` × (connect + read). Ese mínimo es lo que protege a los vecinos de partición.

## Goals / Non-Goals

**Goals:**

- Que la decisión de abrir no dependa del volumen del cliente.
- Poder asignar a un cliente un perfil distinto sin tocar código.
- Mantener los topes que protegen a los vecinos de partición, la regla `wait < retry.delay` y la clasificación de fallos.

**Non-Goals:**

- Config por cliente en la base, editable en caliente, o expuesta en el manager.
- Timeouts por cliente.
- Perfiles que cambien el umbral, la espera, las llamadas de prueba o qué cuenta como fallo.

## Decisions

### 1. Default con ventana por tiempo

| Propiedad | Antes | Ahora | Motivo |
|---|---|---|---|
| `sliding-window-type` | `COUNT_BASED` | `TIME_BASED` | La ventana cubre el mismo tiempo para cualquier volumen |
| `sliding-window-size` | 20 | 60 (segundos) | Un blip de pocos segundos en un cliente de alto volumen no supera el 50% |
| `minimum-number-of-calls` | 5 | 10 | Ver abajo |

El resto de la config no cambia.

**Por qué un mínimo de 10:** el mínimo acota cuánto bloquea la partición un cliente que hace timeout en todo antes de abrir: 10 × (250 + 500 ms), unos 7,5 s. Con 20 serían unos 15 s, y esos clientes son justo los que más bloquean. Con 10, un cliente necesita al menos 10 llamadas por minuto para quedar protegido por el default; los de menos volumen se cubren con un perfil (decisión 2).

**Alternativa descartada:** mantener `COUNT_BASED` y resolver todo con perfiles por volumen. Obliga a perfilar a cada cliente grande, y el default seguiría siendo frágil para cualquier cliente nuevo.

### 2. Perfiles como `configs` de Resilience4j con `base-config: default`

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default: { ... }
      bajo-volumen:
        base-config: default
        sliding-window-size: 300
        minimum-number-of-calls: 5
```

- **Qué cambia un perfil.** Un perfil solo cambia `sliding-window-size` y `minimum-number-of-calls`. Hereda la espera, las pruebas, el `max-wait` y el umbral. Así la regla `wait < retry.delay` vive en un solo lugar y ningún perfil la puede romper.
- **Cómo se respeta.** El comentario de `application.yml` lo dice, y un test de arranque verifica que cada perfil tenga la misma espera que el default.
- **Por qué `bajo-volumen` con 300 s y mínimo 5.** Un cliente de pocas llamadas por minuto alcanza 5 en la ventana. El bloqueo antes de abrir queda en unos 3,75 s, como el diseño anterior.

### 3. La asignación cliente → perfil es `instances.<client_id>.base-config`

- **Cómo resuelve Resilience4j.** Crea al arrancar las `instances` declaradas, cada una con su config. Después, `registry.circuitBreaker(clientId)` devuelve esa instancia si existe, o crea una con `default`. No hay cambios en el adapter.
- **Logs y métricas.** `CircuitStateLogging` ya se suscribe a los breakers existentes al construirse. La instancia creada al arrancar loguea sus transiciones igual que las demás. La métrica `resilience4j_circuitbreaker_state` aparece desde el arranque para los clientes con perfil, en `closed`.

**Dónde vive la asignación.**

- **Qué va en cada lugar.** `application.yml` define los perfiles, que son genéricos. La asignación de clientes concretos es dato de cada entorno y no va ahí.
- **Por qué `SPRING_APPLICATION_JSON` en compose.** En local, `docker-compose.yml` asigna `CLIENT008` con:

  ```yaml
  SPRING_APPLICATION_JSON: '{"resilience4j":{"circuitbreaker":{"instances":{"CLIENT008":{"base-config":"bajo-volumen"}}}}}'
  ```

  Una variable de entorno común no sirve. Spring pasa a minúsculas las claves de mapa que vienen de variables de entorno, así que se crearía `client008`, que no coincide con el `client_id`. JSON y YAML conservan las mayúsculas.

**Alternativa descartada:** `instances` en `application.yml`. Mezcla clientes concretos con la config de la aplicación, y en otro entorno habría que sobreescribir el archivo entero.

### 4. Tests

- **Arranque (`NotificatorApplicationTest`).**
  - `clientBreakersUseTheDefaultConfig` pasa a verificar `TIME_BASED`, 60 y 10.
  - Un test nuevo, con la propiedad `resilience4j.circuitbreaker.instances.CLIENT008.base-config=bajo-volumen` en el test, verifica:
    - que `CLIENT008` toma ventana 300 y mínimo 5;
    - que hereda umbral, espera, pruebas y `max-wait` del default;
    - que otro cliente sigue con el default.
- **Adapter (`RestClientWebhookClientTest`).** Arma su propio registry con una config chica y no depende de la ventana de producción, así que no cambia.
- **Escenarios del spec.** Los escenarios de ventana por tiempo se verifican con un test unitario sobre un breaker con ventana por tiempo y un `Clock` fijo, si Resilience4j lo permite. Si no, se verifican sobre la config y en vivo.

## Risks / Trade-offs

- [Un cliente con menos de 10 llamadas por minuto nunca abre con el default] → Aceptado. Hace pocas llamadas y bloquea poco la partición. Si hace falta protegerlo, se le asigna `bajo-volumen`.
- [Con 10 timeouts antes de abrir, el bloqueo máximo pasa de unos 4 s a unos 7,5 s] → Es el costo de no decidir con 5 muestras. Sigue acotado y se puede bajar con un perfil.
- [La ventana por tiempo de Resilience4j agrega en buckets de 1 s, y la de 300 s mantiene 300 buckets por cliente con ese perfil] → Despreciable en memoria.
- [Cambiar un perfil o una asignación requiere reiniciar] → Aceptado para este alcance. La config en caliente está fuera de alcance.
- [Un `client_id` mal escrito en `instances` crea un breaker que nunca se usa, sin error] → Aparece en la métrica de estado con un nombre que no coincide con ningún cliente. Se documenta en el README.

## Migration Plan

- **Orden de archivo.** Archivar `webhook-circuit-breaker` antes que este change, porque el delta modifica un requisito que ese change agrega.
- **Despliegue.** No hay migración de datos. Al reiniciar, los breakers arrancan cerrados con la config nueva y el estado anterior se pierde, igual que en cualquier reinicio.

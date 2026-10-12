# Pruebas

| Archivo | Para qué |
|---|---|
| `manager.postman_collection.json` | API del manager: listado, detalle, replay y errores |
| `webhook-mock.postman_collection.json` | Configura qué responde el mock de webhooks (código y demora, por cliente) |
| `suscribir.sh` | Suscribe un cliente a un webhook |

## Con un webhook real

1. Levantar el entorno con timeouts para internet (los de defecto, 250 ms y 500 ms, son para el mock local):

   ```sh
   WEBHOOK_CONNECT_TIMEOUT=2s WEBHOOK_READ_TIMEOUT=5s docker compose up -d --build
   ```

2. Suscribir un cliente. Usar uno que no sea `CLIENT001`..`CLIENT008`: el simulador genera eventos para esos clientes y le mandaría cientos por minuto al webhook.

   ```sh
   pruebas/suscribir.sh CLIENT100 https://SU-WEBHOOK                    # todos los event_type
   pruebas/suscribir.sh CLIENT100 https://SU-WEBHOOK credit_transfer    # solo uno
   ```

   Se puede volver a correr para cambiar la URL. Las suscripciones se borran con `docker compose down -v`.

3. Publicar un evento (la key es el `event_id`; cada evento necesita un `event_id` nuevo):

   ```sh
   echo 'EVT-DEMO-1|{"event_id":"EVT-DEMO-1","client_id":"CLIENT100","event_type":"credit_transfer","content":"Transfer of $100","created_at":"2026-10-12T20:00:00Z"}' \
   | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 \
     --topic transactions.events.v1 --property parse.key=true --property key.separator='|'
   ```

4. Ver el resultado con la colección del manager, con la variable `clientId` en `CLIENT100`. También se puede ver en Grafana (`http://localhost:3000`), dashboard **Cliente**.

El webhook recibe un `POST` con los headers `X-Notification-Event-Id`, `X-Event-Id` y `X-Delivery-Attempt`, y un cuerpo como este:

```json
{"notification_event_id":"01a1...","event_id":"EVT-DEMO-1","client_id":"CLIENT100","event_type":"credit_transfer","content":"Transfer of $100","created_at":"2026-10-12T20:00:00Z"}
```

| Respuesta del webhook | Estado del evento |
|---|---|
| 2xx | `DELIVERED` |
| timeout, error de conexión, 408, 429, 5xx | `RETRYING`; se reintenta a los 60 s, 5 min y 25 min, y después queda `FAILED` |
| otro código, incluidos los 3xx (no se siguen redirecciones) | `FAILED` |

Un evento `FAILED` se puede reenviar con *Replay → Replay (202)* de la colección del manager. Para ver los reintentos sin esperar tanto, sumar `RETRY_DELAY=5s` al `docker compose up` (quedan en 5 s, 25 s y 125 s).

## Con el mock

Sin webhook externo, todo se prueba contra el mock (`http://localhost:8090`), que ya tiene suscriptos a `CLIENT001`..`CLIENT008`. Hay dos formas de generar tráfico:

- El simulador: `docker compose --profile simulator up -d transaction-simulator`.
- El paso 3 de arriba, con un cliente suscripto al mock:

  ```sh
  pruebas/suscribir.sh CLIENT100 http://webhook-mock:8090/webhooks/CLIENT100
  ```

La colección del mock cambia lo que responde cada cliente (503, 429, demoras) para ver reintentos y circuit breaker.

# cobre-notificator

## Kafka

Broker de un nodo en modo KRaft (`apache/kafka:4.1.0`), definido en `docker-compose.yml`. La auto-creación de tópicos está deshabilitada: los tópicos los crea el servicio one-shot `kafka-init` con [kafka/create-topics.sh](kafka/create-topics.sh), que es idempotente.

| Tópico | Rol | Particiones | Retención |
|---|---|---|---|
| `transactions.events.v1` | principal, inicio del flujo | 3 | 7 días |
| `notificator.retry.v1-0` | reintento 1 (60 s) | 3 | 1 día |
| `notificator.retry.v1-1` | reintento 2 (300 s) | 3 | 1 día |
| `notificator.retry.v1-2` | reintento 3 (1500 s) | 3 | 1 día |
| `notificator.dlt.v1` | reintentos agotados | 3 | 14 días |
| `notifications.replay-requested.v1` | replays pedidos desde el manager | 3 | 7 días |

La key de todos los mensajes es el `client_id`.

Conexión:
- Desde los contenedores: `kafka:9092`.
- Desde el host: `localhost:9094`, publicado solo en `127.0.0.1`.

```sh
# Ver tópicos, particiones y configuración
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe

# Retención de un tópico
docker compose exec kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9092 \
  --describe --entity-type topics --entity-name transactions.events.v1

# Reaprovisionar: crea los que falten y reaplica la retención
docker compose up kafka-init
```

El script nunca cambia las particiones de un tópico existente. Para empezar de cero, usar `docker compose down -v`.

### Producción (no implementado)

- Autenticación y cifrado con SASL/TLS, y ACLs por aplicación:
  - el notificator consume el principal y los internos, y produce en los internos;
  - el manager solo produce en `notifications.replay-requested.v1`.
- Replication factor 3 con `min.insync.replicas=2`.
- Credenciales desde el secret manager.

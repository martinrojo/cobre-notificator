# Design

## Context

`manager/` está vacío. El repo ya tiene `docker-compose.yml` (solo MariaDB), `.env.example` y `.gitignore`. Localmente hay Java 25, Maven 3.10 y Docker. Este es el primer proyecto Java del repo, así que sienta el patrón que van a copiar notificator y gateway.

## Goals / Non-Goals

**Goals:**
- Proyecto que compila, testea, verifica formato y corre en Docker.
- Estructura hexagonal definida desde el día uno (convención en este design), sin clases de relleno.

**Non-Goals:**
- Parent POM o módulo Maven compartido entre apps: cada app es un proyecto Maven independiente con su propio Dockerfile.
- Healthcheck de Docker sobre `/ping` (se agrega cuando otro servicio dependa del manager, p.ej. el gateway).

## Decisions

### Proyecto Maven standalone con `spring-boot-starter-parent` 4.x
Parent de Spring Boot 4 (última 4.x estable en Maven Central al implementar), `java.version` 25. Dependencias: `spring-boot-starter-webmvc` y `spring-boot-starter-webmvc-test` (scope test; trae JUnit 5 y Mockito). En Spring Boot 4 `spring-boot-starter-web` quedó como alias deprecado de `-webmvc`, por eso se usa el nombre nuevo.
Alternativa descartada: multi-módulo con parent propio en la raíz. Acopla los builds de las 3 apps y complica los Dockerfiles; no hay código compartido que lo justifique.

### Paquetes hexagonales bajo `com.cobre.manager`
```
com.cobre.manager
├── ManagerApplication
├── domain            (modelo y ports; sin Spring/Kafka/JDBC)
│   ├── model
│   └── port
│       ├── in        (casos de uso)
│       └── out       (repositorio, publisher)
├── application       (servicios que implementan ports in)
└── infrastructure
    └── adapter
        ├── in/web    (controllers)
        └── out       (persistence, messaging)
```
Sin `package-info.java` ni clases de relleno: el árbol de arriba es la convención y cada paquete aparece cuando recibe su primera clase (git no versiona carpetas vacías). No se crean ports ni use cases ficticios.

### `/ping` como adapter de entrada sin use case
`PingController` vive en `infrastructure.adapter.in.web` y devuelve `"pong"` directamente con `text/plain`. Es un chequeo técnico, no lógica de negocio. Pasarlo por un port in y un servicio sería ceremonia sin valor.
Alternativa descartada: Actuator `/actuator/health`. Lo pidieron explícitamente como `/ping`, y Actuator llega con el cambio de observabilidad.

### fmt-maven-plugin con goal `check`
`com.spotify.fmt:fmt-maven-plugin` (última versión estable) con el goal `check`, que corre por defecto en la fase `verify`. Para formatear localmente se usa `mvn com.spotify.fmt:fmt-maven-plugin:format`.

### Logs JSON desde el arranque
`logging.structured.format.console: ecs` en `application.yml` (logging estructurado nativo de Spring Boot). `spring.application.name: manager`, para que el campo de servicio salga en cada log.

### Puerto 9290
`server.port: 9290` en `application.yml`. Es el mismo puerto dentro del contenedor y en el host, para no tener un mapeo distinto entre local y Docker.

### Dockerfile multi-stage
- Build: `maven:3-eclipse-temurin-25`. Copia primero `pom.xml` y corre `mvn dependency:go-offline` para cachear la capa de dependencias. Después copia `src` y corre `mvn -B verify`, que incluye tests y chequeo de formato.
- Runtime: `eclipse-temurin:25-jre`, usuario no root, `ENTRYPOINT ["java", "-jar", "/app/app.jar"]`, `EXPOSE 9290`.
Alternativa descartada: build con `-DskipTests`. El build tarda un poco más, pero una imagen nunca sale con tests rotos o código sin formatear.

### docker-compose
Servicio `manager` con `build: ./manager` y puerto `9290:9290`. Sin `depends_on` hacia MariaDB porque este cambio no se conecta a la DB.

## Risks / Trade-offs

- [Spring Boot 4 renombró starters y paquetes de test (p.ej. `@WebMvcTest` ahora vive en `org.springframework.boot.webmvc.test.autoconfigure`)] → Validarlo con el test del controller durante la implementación, en vez de asumir los imports de Boot 3.
- [Exponer 9290 en el host deja el manager accesible sin pasar por el gateway] → Aceptable en local mientras no existe el gateway. Cuando se agregue, se quita el `ports` del manager y se expone solo el gateway (OWASP A01, Broken Access Control).
- [El build en Docker corre los tests y es más lento] → Se mitiga con la capa cacheada de dependencias.

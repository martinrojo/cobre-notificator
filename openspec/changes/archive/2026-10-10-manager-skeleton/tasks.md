# Tasks

## 1. Proyecto Maven

- [x] 1.1 Crear `manager/pom.xml` con `spring-boot-starter-parent` 4.x (última estable), Java 25, `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test` (test) y `fmt-maven-plugin` con el goal `check`. Verificar con `mvn -B -f manager/pom.xml validate`
- [x] 1.2 Crear `ManagerApplication` y `application.yml` (`spring.application.name: manager`, `server.port: 9290`, `logging.structured.format.console: ecs`). Verificar que `mvn -f manager/pom.xml spring-boot:run` arranca y loguea en JSON
- [x] 1.3 Crear los paquetes hexagonales (`domain.model`, `domain.port.in`, `domain.port.out`, `application`, `infrastructure.adapter.in.web`, `infrastructure.adapter.out`), sin `package-info.java` ni clases de relleno. Verificar que `mvn -f manager/pom.xml verify` pasa, incluido el chequeo de formato

## 2. Endpoint /ping

- [x] 2.1 Crear `PingController` en `infrastructure.adapter.in.web` que responda `GET /ping` con 200 y `pong` en `text/plain`, con inyección por constructor si hiciera falta
- [x] 2.2 Agregar `PingControllerTest` con `@WebMvcTest` (imports de Spring Boot 4) que cubra los escenarios de la spec: 200 + `pong`, con y sin `X-Client-Id`. Verificar con `mvn -f manager/pom.xml verify`

## 3. Docker

- [x] 3.1 Crear `manager/Dockerfile` multi-stage (build `maven:3-eclipse-temurin-25` con capa de dependencias cacheada y `mvn -B verify`; runtime `eclipse-temurin:25-jre` con usuario no root). Verificar con `docker build -t cobre-manager manager/`
- [x] 3.2 Agregar el servicio `manager` (`build: ./manager`, puerto `9290:9290`) a `docker-compose.yml`. Verificar con `docker compose up -d --build manager` y `curl -i localhost:9290/ping`, que debe devolver 200 y `pong`

## Workflow follow-up

- Archivar el cambio una vez revisado.

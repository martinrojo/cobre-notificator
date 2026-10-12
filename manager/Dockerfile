FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q verify

FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home app
USER app
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar
EXPOSE 9290
ENTRYPOINT ["java", "-Xms2g", "-Xmx2g", "-jar", "/app/app.jar"]

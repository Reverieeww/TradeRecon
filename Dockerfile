FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw -B -ntp package -DskipTests
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /build/target/trade-recon-0.1.0-SNAPSHOT.jar app.jar
USER app
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=5 CMD curl --fail --silent http://127.0.0.1:8080/actuator/health || exit 1
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]


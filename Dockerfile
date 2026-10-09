# Stage 1: build the jar
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# maven.test.skip skips compiling AND running tests, so the image builds without a database
RUN mvn -q -B -Dmaven.test.skip=true package

# Stage 2: run it in a small image
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system app
COPY --from=build /app/target/*.jar app.jar
# Free instances have about 512 MB of RAM, so keep the JVM small
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

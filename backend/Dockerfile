FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21.0.6_7-jre-jammy
RUN groupadd -r seatlock && useradd -r -g seatlock seatlock
WORKDIR /app
COPY --from=build /build/target/seatlock-1.0.0.jar app.jar
USER seatlock
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-jar","app.jar"]

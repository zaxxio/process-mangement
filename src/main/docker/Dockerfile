# Source Build
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY app.jar .
RUN mvn clean package -DskipTests

# Docker Build Stage
FROM openjdk:17-jdk-slim
RUN apt-get update && apt-get install -y procps
WORKDIR /app
COPY --from=build /app/target/process-management.jar ./process-management.jar
COPY --from=build /app/app.jar ./app.jar

EXPOSE 8080
ENV JAVA_OPTS="-Xms512m -Xmx1024m"
ENTRYPOINT ["java", "-jar", "process-management.jar"]
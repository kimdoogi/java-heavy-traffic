FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/mock-external-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

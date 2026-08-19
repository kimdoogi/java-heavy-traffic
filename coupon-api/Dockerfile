# 호스트에서 ./gradlew :coupon-api:bootJar 로 만든 jar를 담는다 (빌드 속도 우선, scripts/build.sh 참고)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/coupon-api-*.jar app.jar
EXPOSE 8080
# JVM 옵션(-Xmx, GC, -Djdk.tracePinnedThreads 등)은 compose 에서 JAVA_TOOL_OPTIONS 로 주입
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

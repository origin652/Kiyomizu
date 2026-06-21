FROM eclipse-temurin:17-jdk AS build

WORKDIR /workspace

COPY gradle-9.5.1-bin.zip /tmp/gradle-9.5.1-bin.zip
COPY gradle /workspace/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /workspace/
RUN sed -i 's/\r$//' /workspace/gradlew \
    && sed -i 's#distributionUrl=.*#distributionUrl=file\\:///tmp/gradle-9.5.1-bin.zip#' /workspace/gradle/wrapper/gradle-wrapper.properties \
    && chmod +x /workspace/gradlew

COPY src /workspace/src
RUN /workspace/gradlew --no-daemon fatJar

FROM eclipse-temurin:17-jre

WORKDIR /app

ENV HOST=0.0.0.0 \
    PORT=8787 \
    KIYOMIZU_DB_FILE=/data/kiyomizu_companion.db

COPY --from=build /workspace/build/libs/*-all.jar /app/kiyomizu.jar

VOLUME ["/data"]
EXPOSE 8787

ENTRYPOINT ["java", "-jar", "/app/kiyomizu.jar"]

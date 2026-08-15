# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -e dependency:go-offline
COPY src ./src
# scripts/7-Zip/7z.exe is a Windows binary; neutralize the antrun pack step on Linux
RUN mvn -q package -DskipTests -Dsevenzip.executable=/bin/true

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg unzip && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/weibo-plus.jar app.jar
# Pre-install Playwright Chromium (QR login) with its OS deps
RUN unzip -q app.jar -d /tmp/extracted && \
    java -cp "/tmp/extracted/BOOT-INF/lib/*" com.microsoft.playwright.CLI install --with-deps chromium && \
    rm -rf /tmp/extracted
ENV WEIBO_DATABASE_PATH=/data/weibo.db
WORKDIR /data
EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

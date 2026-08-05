# syntax=docker/dockerfile:1

# 빌드 단계
#
# 의존성 해석과 소스 컴파일을 분리해 소스만 바뀐 경우 의존성 레이어를 재사용합니다.
FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /workspace

# Gradle wrapper 와 빌드 스크립트를 먼저 복사합니다. 이 파일들이 바뀌지 않으면
# 아래 의존성 다운로드 레이어가 캐시에서 재사용됩니다.
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./

RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src

# 테스트는 CI 에서 실행합니다. 이미지 빌드는 패키징만 담당합니다.
RUN ./gradlew bootJar --no-daemon -x test \
    && boot_jar="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "$boot_jar" \
    && cp "$boot_jar" /workspace/app.jar

# 실행 단계
FROM eclipse-temurin:17-jre-alpine AS runtime

# 애플리케이션을 비특권 사용자로 실행합니다.
RUN addgroup -S mopl && adduser -S mopl -G mopl

WORKDIR /app

COPY --from=build --chown=mopl:mopl /workspace/app.jar app.jar

USER mopl

EXPOSE 8080

# 컨테이너 메모리 한도를 힙 산정에 반영합니다. ECS task 의 memory 설정을 바꾸면
# JVM 힙도 따라갑니다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# ALB health check 대상과 같은 엔드포인트를 사용합니다.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

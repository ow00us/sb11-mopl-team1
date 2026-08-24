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

# liveness probe 를 씁니다. 전체 /actuator/health 는 Kafka 리스너 중지처럼 프로세스를 다시
# 띄운다고 풀리지 않는 상태까지 DOWN 으로 집계합니다. 그것을 컨테이너 재시작 조건으로 두면
# 원인은 그대로인 채 재시작만 반복됩니다.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

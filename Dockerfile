# Stages, in the order Docker sees them:
#   runtime    - JRE, user and JVM flags shared by every image
#   local-jar  - runtime + an already built automately-server/target/automately.jar
#                (used by the Testcontainers integration test, so it tests the exact jar `mvn package` made)
#   build      - compiles the jar inside Docker
#   release    - runtime + the jar from `build` (the default target)
# local-jar comes before build so that building it never runs the Maven build.

# Javet ships glibc native libraries, so the image must not be Alpine/musl.
FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --uid 10001 automately
WORKDIR /opt/automately
EXPOSE 8080 8000 5701
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=50 \
  --add-modules java.se \
  --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.management/sun.management=ALL-UNNAMED \
  --add-opens jdk.management/com.sun.management.internal=ALL-UNNAMED"
ENTRYPOINT ["java", "-jar", "/opt/automately/automately.jar"]

FROM runtime AS local-jar
COPY automately-server/target/automately.jar /opt/automately/automately.jar
USER automately

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY automately-core/pom.xml automately-core/
COPY automately-runtime-javet/pom.xml automately-runtime-javet/
COPY automately-server/pom.xml automately-server/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline -DexcludeReactor=true || true
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package

FROM runtime AS release
COPY --from=build /src/automately-server/target/automately.jar /opt/automately/automately.jar
USER automately

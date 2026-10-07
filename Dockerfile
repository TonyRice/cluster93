# Stages, in the order Docker sees them:
#   runtime    - JRE, user and JVM flags shared by every image
#   local-jar  - runtime + an already built automately-server/target/automately.jar
#                (used by the Testcontainers integration test, so it tests the exact jar `mvn package` made)
#   build      - compiles the jar inside Docker
#   release    - runtime + the jar from `build` (the default target)
# local-jar comes before build so that building it never runs the Maven build.

# Javet ships glibc native libraries, so the image must not be Alpine/musl.
FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --uid 10001 automately \
 && mkdir -p /var/lib/automately && chown automately /var/lib/automately
WORKDIR /opt/automately
# API, ingress, the internal endpoint (cluster-only), Ignite discovery and communication.
EXPOSE 8080 8000 8090 9090 5701 5801
# Durable data (Ignite persistence). Mount a volume here; without AUTOMATELY_DATA_DIR data is in memory only.
VOLUME /var/lib/automately
ENV AUTOMATELY_DATA_DIR=/var/lib/automately
# Heap is kept below half the container so Ignite's off-heap data region and V8 isolates fit beside it.
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=40 \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.management/com.sun.jmx.mbeanserver=ALL-UNNAMED \
  --add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED \
  --add-opens=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED \
  --add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens=java.base/java.math=ALL-UNNAMED \
  --add-opens=java.sql/java.sql=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.time=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-opens=java.management/sun.management=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"
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

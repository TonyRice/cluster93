# Stages, in the order Docker sees them:
#   runtime    - JRE, user and JVM flags shared by every image
#   local-jar  - runtime + an already built cluster93-server/target/c93.jar
#                (used by the Testcontainers integration test, so it tests the exact jar `mvn package` made)
#   build      - compiles the jar inside Docker
#   release    - runtime + the jar from `build` (the default target)
# local-jar comes before build so that building it never runs the Maven build.

# Javet ships glibc native libraries, so the image must not be Alpine/musl.
FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --uid 10001 c93 \
 && mkdir -p /var/lib/cluster93 && chown c93 /var/lib/cluster93
WORKDIR /opt/cluster93
# API, ingress, the internal endpoint (cluster-only), Ignite discovery and communication.
EXPOSE 8080 8000 8090 9090 5701 5801
# Durable data (Ignite persistence). Mount a volume here; without C93_DATA_DIR data is in memory only.
VOLUME /var/lib/cluster93
ENV C93_DATA_DIR=/var/lib/cluster93
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
ENTRYPOINT ["java", "-jar", "/opt/cluster93/c93.jar"]

FROM runtime AS local-jar
COPY cluster93-server/target/c93.jar /opt/cluster93/c93.jar
USER c93

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY cluster93-tls/pom.xml cluster93-tls/
COPY cluster93-core/pom.xml cluster93-core/
COPY cluster93-runtime-js/pom.xml cluster93-runtime-js/
COPY cluster93-runtime-javet/pom.xml cluster93-runtime-javet/
COPY cluster93-runtime-graaljs/pom.xml cluster93-runtime-graaljs/
COPY cluster93-worker/pom.xml cluster93-worker/
COPY cluster93-server/pom.xml cluster93-server/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline -DexcludeReactor=true || true
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package

FROM runtime AS release
COPY --from=build /src/cluster93-server/target/c93.jar /opt/cluster93/c93.jar
USER c93

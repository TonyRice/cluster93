# Build
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

# Run. Javet ships glibc native libraries, so the image must not be Alpine/musl.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 automately
COPY --from=build /src/automately-server/target/automately.jar /opt/automately/automately.jar
WORKDIR /opt/automately
USER automately
EXPOSE 8080 5701
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=50 \
  --add-modules java.se \
  --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.management/sun.management=ALL-UNNAMED \
  --add-opens jdk.management/com.sun.management.internal=ALL-UNNAMED"
ENTRYPOINT ["java", "-jar", "/opt/automately/automately.jar"]

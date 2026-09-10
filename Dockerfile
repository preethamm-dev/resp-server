# Build stage. Dependencies are resolved in their own layer so that editing source
# does not re-download Maven's world on every build.
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build
RUN apk add --no-cache maven

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
# Tests are skipped here on purpose: they are run by CI against the source tree, and
# repeating them inside the image build would double CI time to prove the same thing.
RUN mvn -B -ntp package -DskipTests

# Runtime stage. A JRE rather than a JDK -- no compiler, no javadoc, no debug tooling
# in the shipped image, which is both smaller and less useful to an attacker.
FROM eclipse-temurin:21-jre-alpine

# A non-root user. If the process is ever compromised, it should not own the filesystem.
RUN addgroup -S resp && adduser -S -G resp resp

WORKDIR /app
COPY --from=build /build/target/resp-server.jar ./resp-server.jar

# Persistence lives on a volume so an AOF survives the container it was written by.
RUN mkdir -p /data && chown -R resp:resp /app /data
VOLUME ["/data"]

USER resp
EXPOSE 6380

# 0.0.0.0 rather than 127.0.0.1: inside a container, loopback is unreachable from the
# host, so the default bind address would make the published port appear dead.
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75", "-jar", "resp-server.jar", "--bind", "0.0.0.0"]
CMD ["--port", "6380"]

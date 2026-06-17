# Multi-stage build: compile with the JDK, run on the JRE.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon clean :jediscore-server:installDist -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/jediscore-server/build/install/jediscore/ ./
EXPOSE 6379
# Args are passed through to JediCoreServer: [config.conf] [host:port] [--opt value ...]
ENTRYPOINT ["bin/jediscore"]
CMD ["0.0.0.0:6379"]

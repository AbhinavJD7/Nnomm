FROM gradle:8.6-jdk21-alpine AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . /home/gradle/src
RUN gradle installDist --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /home/gradle/src/build/install/nnomm /app/
EXPOSE 8080 8081
ENTRYPOINT ["/app/bin/nnomm"]

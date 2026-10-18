FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/install/nnomm /app/
EXPOSE 8080 8081
ENTRYPOINT ["/app/bin/nnomm"]

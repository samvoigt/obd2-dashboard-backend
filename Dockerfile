# Built by Cloud Build on `gcloud run deploy --source .`; Docker is not needed locally.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :server:buildFatJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/server/build/libs/server.jar server.jar
# Cloud Run sets PORT; the server reads it and defaults to 8080.
EXPOSE 8080
CMD ["java", "-jar", "server.jar"]

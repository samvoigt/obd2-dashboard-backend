# Built by Cloud Build (scripts/deploy.sh, cloudbuild.yaml); Docker is not needed locally.

# The website (web/), with the same Node major as local builds (24, LTS).
FROM node:24-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# The server, with the site built above taken in as-is (-PwebDist), so this stage needs no Node.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
COPY --from=web /web/dist /web-dist
RUN ./gradlew --no-daemon :server:buildFatJar -PwebDist=/web-dist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/server/build/libs/server.jar server.jar
# Cloud Run sets PORT; the server reads it and defaults to 8080.
EXPOSE 8080
CMD ["java", "-jar", "server.jar"]

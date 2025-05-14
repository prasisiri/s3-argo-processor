FROM gradle:7.6.1-jdk11 AS build
WORKDIR /app
COPY . .
RUN gradle build --no-daemon

FROM openjdk:11-jre-slim
WORKDIR /app
COPY --from=build /app/build/distributions/app.tar /app/
RUN tar -xf app.tar && rm app.tar

ENTRYPOINT ["/app/app/bin/app"] 
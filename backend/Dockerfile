FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn --batch-mode dependency:go-offline

COPY src ./src
RUN mvn --batch-mode -DskipTests package

FROM alpine:3.20 AS parser
RUN apk add --no-cache curl unzip
RUN curl -fL --retry 3 \
      https://github.com/opendataloader-project/opendataloader-pdf/releases/download/v2.5.17/opendataloader-pdf-cli-2.5.17.zip \
      -o /tmp/parser.zip \
    && echo '09ba781053f73f9815ae3877bbbeb2d2e6f01d77c04c9d4ef6a30cc9234b5a7f  /tmp/parser.zip' | sha256sum -c - \
    && mkdir -p /opt/parser \
    && unzip -j /tmp/parser.zip opendataloader-pdf-cli-2.5.17.jar -d /opt/parser

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/target/*.jar app.jar
COPY --from=parser /opt/parser/opendataloader-pdf-cli-2.5.17.jar /opt/parser.jar
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

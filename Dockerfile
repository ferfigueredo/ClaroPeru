# syntax=docker/dockerfile:1.6

ARG BUILDER_IMAGE=registry.access.redhat.com/ubi9/openjdk-21:1.18
ARG RUNTIME_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:1.18

FROM ${BUILDER_IMAGE} AS builder
USER root

ENV MAVEN_HOME=/opt/maven
ENV MAVEN_OPTS="-Dmaven.repo.local=/workspace/.m2"

WORKDIR /workspace/app

RUN microdnf install -y gzip

RUN mkdir -p /workspace/.m2

COPY ./pom.xml ./
COPY ./src ./src
COPY ./libs ./libs
COPY ./settings.xml ./settings.xml
COPY ./.mvn ./.mvn
COPY ./mvnw ./mvnw

#RUN ./mvnw -s /workspace/app/settings.xml clean compile dependency:copy-dependencies -DoutputDirectory=target/dependency -DskipTests
RUN ./mvnw -s /workspace/app/settings.xml clean package -DskipTests

# --debug--
RUN find target/dependency -name "ojdbc*.jar" -o -name "oracle" | head -5

# --runtime--
FROM ${RUNTIME_IMAGE}
WORKDIR /opt/app

# --copiar el jar--
#COPY --from=builder /workspace/app/target/classes ./classes
#COPY --from=builder /workspace/app/target/dependency ./dependency
#COPY --from=builder /workspace/app/libs ./libs
COPY --from=builder /workspace/app/target/*.jar app.jar

RUN echo "Listando Drivers oracle" && find . -name "ojdbc*.jar" -o -name "oracle" | head -10


EXPOSE 8080

# Formamos la jvm a incluir los jars de libs en el classpath
#ENTRYPOINT ["java", "-Doracle.jdbc.timezoneAsRegion=false", "-Duser.timezone=america/Lima", "-cp", "classes:dependency/:libs/", "com.claro.internationalcoverage.InternationalCoverageApp"]
ENTRYPOINT ["java", "-Dspring.config.location=/config/application.properties", "-Doracle.jdbc.timezoneAsRegion=false", "-Duser.timezone=america/Lima", "-cp", "classes:dependency/:libs/", "-jar", "app.jar"]
#ENTRYPOINT ["/workspace/app/mvnw", "-Doracle.jdbc.timezoneAsRegion=false", "-Duser.timezone=america/Lima", "spring-boot:run"]

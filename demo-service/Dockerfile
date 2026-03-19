FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY target/demo-service-*.jar app.jar

RUN mkdir -p /data/runs
EXPOSE 8081 9010

ENTRYPOINT ["java", \
    "-Dcom.sun.management.jmxremote", \
    "-Dcom.sun.management.jmxremote.port=9010", \
    "-Dcom.sun.management.jmxremote.rmi.port=9010", \
    "-Dcom.sun.management.jmxremote.local.only=false", \
    "-Dcom.sun.management.jmxremote.authenticate=false", \
    "-Dcom.sun.management.jmxremote.ssl=false", \
    "-Djava.rmi.server.hostname=demo-service", \
    "-jar", "app.jar"]

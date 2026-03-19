FROM eclipse-temurin:17-jre-jammy

# Install JMeter
ARG JMETER_VERSION=5.6.2
RUN apt-get update && apt-get install -y wget bash unzip && \
    wget -q https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz -O /tmp/jmeter.tgz && \
    mkdir -p /opt/jmeter && \
    tar -xzf /tmp/jmeter.tgz -C /opt/jmeter --strip-components=1 && \
    rm /tmp/jmeter.tgz

# Install Eclipse MAT (Memory Analyzer Tool) for Heap Dump analysis
# RUN curl -L "https://ftp.osuosl.org/pub/eclipse/mat/1.15.0/rcp/MemoryAnalyzer-1.15.0.20231206-linux.gtk.x86_64.zip" -o /tmp/mat.zip && \
#     unzip -q /tmp/mat.zip -d /opt && \
#     mv /opt/mat /opt/memoryanalyzer && \
#     rm /tmp/mat.zip

WORKDIR /app
COPY target/platform-core-*.jar app.jar

RUN mkdir -p /data/runs /config/teams

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Dplatform.mat-home=/opt/memoryanalyzer", \
    "-jar", "app.jar"]

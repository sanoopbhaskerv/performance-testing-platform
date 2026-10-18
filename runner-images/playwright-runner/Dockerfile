# Build from repository root: docker build -f runner-images/playwright-runner/Dockerfile .
FROM mcr.microsoft.com/playwright:v1.62.1-noble
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PERF_PLAYWRIGHT_VERSION=1.62.1 \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
RUN apt-get update && apt-get install -y --no-install-recommends python3-venv ca-certificates git \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/aws-cli \
    && /opt/aws-cli/bin/pip install --no-cache-dir awscli==1.36.22
ENV PATH=/opt/aws-cli/bin:$PATH
WORKDIR /opt/perf-runner
COPY runner-images/playwright-runner/package*.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY engines/engine-playwright/src/main/resources/playwright-runner/*.mjs ./
COPY runner-images/playwright-runner/progress.js ./
COPY runner-images/playwright-runner/entrypoint.sh /usr/local/bin/run-playwright-shard
RUN chmod 0555 /usr/local/bin/run-playwright-shard
USER 10001:10001
WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/run-playwright-shard"]

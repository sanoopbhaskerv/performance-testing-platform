FROM mcr.microsoft.com/playwright:v1.50.0-noble

ARG LIGHTHOUSE_VERSION=12.4.0

ENV NODE_ENV=production
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

RUN apt-get update \
    && apt-get install -y --no-install-recommends awscli ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g lighthouse@${LIGHTHOUSE_VERSION}

COPY entrypoint.sh /usr/local/bin/run-playwright-shard
RUN chmod 0555 /usr/local/bin/run-playwright-shard

USER pwuser
WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/run-playwright-shard"]

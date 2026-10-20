# ─────────────────────────────────────────────────────────────────────────────
#  JMeter runner image.
#
#  One shard of one run, then exit. It fetches the plan it was told to execute,
#  runs it, uploads what it produced, and stops. It makes no decisions: it does
#  not choose a target, a duration, a team or a plan — all of that is fixed by
#  the control plane before this container starts.
#
#  Built to satisfy the contract in RunnerJobManifest. If you change the PERF_*
#  variables here, change them there; the manifest's contract tests are what keep
#  the two honest.
# ─────────────────────────────────────────────────────────────────────────────

# Pinned by digest, not just by tag. A tag can be repointed, and a runner image
# that silently changed would mean two runs of the same immutable definition
# version executed on different builds while the plan digest still matched.
FROM eclipse-temurin:17-jre-jammy@sha256:475d8e96b4b2bfe08999e5e854755c773af1581acdf959a4545d88f0696a2339

ARG JMETER_VERSION=5.6.3
ARG JMETER_PLUGINS_MANAGER_VERSION=1.10
ARG CMDRUNNER_VERSION=2.3
ARG JMETER_DISTRIBUTION_BASE_URL=https://archive.apache.org/dist/jmeter/binaries

# `latest` is never used, and every download is checksum-verified below: an image
# build that silently accepted a corrupted or substituted archive would put an
# unknown load generator in front of a production system.
ENV JMETER_HOME=/opt/jmeter \
    PATH=/opt/jmeter/bin:$PATH

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates unzip; \
    rm -rf /var/lib/apt/lists/*; \
    \
    curl --connect-timeout 15 --max-time 300 -fsSL "${JMETER_DISTRIBUTION_BASE_URL}/apache-jmeter-${JMETER_VERSION}.tgz" \
        -o /tmp/jmeter.tgz; \
    curl --connect-timeout 15 --max-time 60 -fsSL "${JMETER_DISTRIBUTION_BASE_URL}/apache-jmeter-${JMETER_VERSION}.tgz.sha512" \
        -o /tmp/jmeter.tgz.sha512; \
    # The published checksum file names the archive; check the bytes we actually got.
    echo "$(cut -d' ' -f1 /tmp/jmeter.tgz.sha512)  /tmp/jmeter.tgz" | sha512sum -c -; \
    \
    mkdir -p "${JMETER_HOME}"; \
    tar -xzf /tmp/jmeter.tgz -C "${JMETER_HOME}" --strip-components=1; \
    rm /tmp/jmeter.tgz /tmp/jmeter.tgz.sha512

# ConcurrencyThreadGroup, so load stages can ramp smoothly rather than stepping.
# Separate thread groups with staggered starts stress a system differently from a
# smooth ramp — connection pools and autoscalers respond to the transition, not
# only to the level. See docs/operations/runner-calibration.md.
RUN set -eux; \
    curl -fsSL "https://repo1.maven.org/maven2/kg/apc/jmeter-plugins-manager/${JMETER_PLUGINS_MANAGER_VERSION}/jmeter-plugins-manager-${JMETER_PLUGINS_MANAGER_VERSION}.jar" \
        -o "${JMETER_HOME}/lib/ext/jmeter-plugins-manager.jar"; \
    # The versioned filename matters: PluginsManagerCMD.sh looks for exactly
    # lib/cmdrunner-${CMDRUNNER_VERSION}.jar and fails on anything else.
    curl -fsSL "https://repo1.maven.org/maven2/kg/apc/cmdrunner/${CMDRUNNER_VERSION}/cmdrunner-${CMDRUNNER_VERSION}.jar" \
        -o "${JMETER_HOME}/lib/cmdrunner-${CMDRUNNER_VERSION}.jar"; \
    java -cp "${JMETER_HOME}/lib/ext/jmeter-plugins-manager.jar" \
        org.jmeterplugins.repository.PluginManagerCMDInstaller; \
    "${JMETER_HOME}/bin/PluginsManagerCMD.sh" install jpgc-casutg; \
    rm -rf /root/.jmeter

# The AWS CLI, for fetching the plan and uploading results. Credentials are never
# baked in: the Pod's projected service-account token is what authorises it.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends awscli; \
    rm -rf /var/lib/apt/lists/*

COPY entrypoint.sh /usr/local/bin/run-shard
# The rolling progress reducer. Python 3 is already present — the apt awscli above is written in it —
# so this adds a file rather than a runtime.
COPY progress.py /usr/local/bin/perf-progress
RUN chmod 0555 /usr/local/bin/run-shard /usr/local/bin/perf-progress

# Matches the securityContext RunnerJobManifest sets. Declared here as well so the
# image is not root-by-default if it is ever run outside that manifest.
RUN useradd --uid 10001 --gid 0 --no-create-home --shell /usr/sbin/nologin runner
USER 10001:0

# Nothing is written outside the mounted workspace: the manifest sets
# readOnlyRootFilesystem, so an image that wrote elsewhere would fail at runtime.
WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/run-shard"]

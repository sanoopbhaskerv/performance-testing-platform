# Optional local/test S3 fixture. Upstream removed the prebuilt 2024 image from its registries.
# Build that exact release's reviewed official source, with no third-party image mirror.
FROM golang:1.23.3-alpine3.20@sha256:c694a4d291a13a9f9d94933395673494fc2cc9d4777b85df3a7e70b3492d3574 AS builder
WORKDIR /source
ADD https://codeload.github.com/minio/minio/tar.gz/cefc43e4daa4cbb490ef6726ea374e26a93eb85e /tmp/minio.tar.gz
RUN echo '19eac4eba99f28056e81e2338edac523a1068a59266bbd700267f55122205e14  /tmp/minio.tar.gz' | sha256sum -c - \
    && tar -xzf /tmp/minio.tar.gz --strip-components=1 -C /source \
    && CGO_ENABLED=0 GOMAXPROCS=2 go build -p 2 -trimpath -buildvcs=false -o /out/minio .

FROM alpine:3.20
COPY --from=builder /out/minio /usr/bin/minio
COPY --from=builder /source/LICENSE /licenses/minio-LICENSE
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN mkdir /data && chown 10001:10001 /data
USER 10001:10001
EXPOSE 9000
ENTRYPOINT ["/usr/bin/minio"]
CMD ["server", "/data"]

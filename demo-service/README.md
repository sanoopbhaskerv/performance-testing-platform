# Demo Service

This service exposes the student APIs used by the performance testing demo:

- `POST /api/students`
- `GET /api/students/{id}`
- `PUT /api/students/{id}`

It uses PostgreSQL and runs Flyway migrations on startup (`demo` schema).

## Prerequisites

- Docker (for PostgreSQL)
- Java 21
- Maven

## 1. Start the local database

From the repository root:

```sh
docker compose -f docker/docker-compose.yml up -d postgres
```

This starts PostgreSQL on `localhost:5432` with the defaults already wired for this service:

- database: `perfplatform`
- user: `perf`
- password: `perf`

## 2. Start the demo service

From the repository root:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f demo-service/pom.xml spring-boot:run
```

The service starts on:

- `http://localhost:8081`

On startup, Flyway creates/applies migrations for schema `demo` and table `demo.students`.

## 3. Quick API check

Create a student:

```sh
curl -s -X POST http://localhost:8081/api/students \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Avery","lastName":"Chen","email":"avery.chen@example.test","program":"Computer Science"}'
```

Get a student:

```sh
curl -s http://localhost:8081/api/students/1
```

Update a student:

```sh
curl -s -X PUT http://localhost:8081/api/students/1 \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Avery","lastName":"Chen","email":"avery.chen@example.test","program":"Data Science"}'
```

## Optional: Override DB connection

You can override the default DB settings with environment variables:

- `DEMO_DB_URL`
- `DEMO_DB_USER`
- `DEMO_DB_PASSWORD`

Example:

```sh
DEMO_DB_URL=jdbc:postgresql://localhost:5432/perfplatform \
DEMO_DB_USER=perf \
DEMO_DB_PASSWORD=perf \
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f demo-service/pom.xml spring-boot:run
```

## Stop services

Stop only PostgreSQL container:

```sh
docker compose -f docker/docker-compose.yml stop postgres
```

Remove PostgreSQL container:

```sh
docker compose -f docker/docker-compose.yml rm -f postgres
```

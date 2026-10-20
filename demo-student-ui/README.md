# Student Registry Demo

This React client exercises the bundled Spring Boot student API:

- `POST /api/students` creates a student.
- `GET /api/students/{id}` retrieves a student.
- `PUT /api/students/{id}` updates a student.

## Run locally

Docker is the only prerequisite for the database. You do not need to install or configure PostgreSQL separately.

From the repository root, start PostgreSQL:

```sh
docker compose -f docker/docker-compose.yml up -d postgres
```

Start the service in a second terminal. Flyway creates the `demo.students` table automatically:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f demo-service/pom.xml spring-boot:run
```

Start the React client in a third terminal:

```sh
cd demo-student-ui
npm run dev
```

Open `http://localhost:5174`. The Vite development proxy forwards `/api` requests to the demo service at `http://localhost:8081`.

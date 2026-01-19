# 🎯 HabitFlow

A full-stack habit tracking application built with microservices architecture, featuring event-driven communication, real-time monitoring, and CI/CD automation.

**Live Demo:** [habitflow.vercel.app](https://habit-tracker-one-murex.vercel.app)

![Lighthouse Score](https://img.shields.io/badge/Accessibility-100-brightgreen) ![Lighthouse Score](https://img.shields.io/badge/Performance-99-brightgreen) ![Build](https://img.shields.io/badge/build-passing-brightgreen)

---

## Tech Stack

| Layer | Technologies |
|-------|--------------|
| **Frontend** | React, Redux Toolkit, Vite, CSS |
| **Backend** | Java 21, Spring Boot 3, Spring Security |
| **Database** | PostgreSQL, JPA/Hibernate |
| **Messaging** | Apache Kafka, Zookeeper |
| **Monitoring** | Prometheus, Grafana, Spring Actuator |
| **Infrastructure** | Docker, Docker Compose |
| **CI/CD** | GitHub Actions |
| **Deployment** | Railway (backend), Vercel (frontend) |

---

## Architecture
![System Architecture Diagram](./docs/architecture.png)

---

## Features

### Core Functionality
- User registration and authentication with JWT
- Create, read, update, delete habits
- Daily habit completion tracking
- Streak calculation and history

### Architecture
- Microservices with separate Auth and Habit services
- Event-driven communication via Apache Kafka
- Service-to-service REST communication for token validation

### Monitoring & Observability
- Prometheus metrics collection
- Grafana dashboards with custom business metrics
- Health checks and JVM metrics via Spring Actuator

### DevOps
- Fully containerized with Docker Compose
- Automated CI/CD with GitHub Actions
- Separate test and production environments

---

## Event-Driven Architecture

### Kafka Topics

**user-events**
```json
{
  "type": "UserRegisteredEvent",
  "userId": 1,
  "email": "user@example.com",
  "registeredAt": "2025-01-12T10:30:00Z"
}
```

**habit-events**
```json
{
  "type": "HabitCreatedEvent",
  "habitId": 1,
  "userId": 1,
  "habitName": "Exercise",
  "frequency": "DAILY",
  "createdAt": "2025-01-12T10:35:00Z"
}
```

```json
{
  "type": "HabitCompletedEvent",
  "habitId": 1,
  "userId": 1,
  "habitName": "Exercise",
  "completionDate": "2025-01-12",
  "completedAt": "2025-01-12T18:00:00Z"
}
```

### Event Flow

1. User registers → Auth Service publishes `UserRegisteredEvent`
2. Habit Service consumes event, logs new user
3. User creates habit → Habit Service publishes `HabitCreatedEvent`
4. User completes habit → Habit Service publishes `HabitCompletedEvent`

---

## Monitoring

### Custom Metrics

| Metric | Service | Description |
|--------|---------|-------------|
| `auth_user_registrations_total` | Auth | Total user registrations |
| `auth_user_logins_total` | Auth | Successful logins |
| `auth_user_logins_failed_total` | Auth | Failed login attempts |
| `habits_created_total` | Habit | Total habits created |
| `habits_completed_total` | Habit | Total habit completions |
| `habits_deleted_total` | Habit | Total habits deleted |

### Grafana Dashboard

The pre-configured dashboard includes:
- Total user registrations and logins
- Habits created and completed
- Request rate per service
- Average response times
- JVM heap memory usage
- Service health status

---

## Getting Started

### Prerequisites

- Java 21
- Node.js 18+
- Docker and Docker Compose
- PostgreSQL (or use Docker)

### Running Locally with Docker

1. **Clone the repository**
   ```bash
   git clone https://github.com/yourusername/habitflow.git
   cd habitflow
   ```

2. **Start all services**
   ```bash
   docker compose up --build
   ```

3. **Access the applications**
   | Service | URL |
   |---------|-----|
   | Frontend | http://localhost:5173 |
   | Auth Service | http://localhost:8081 |
   | Habit Service | http://localhost:8080 |
   | Prometheus | http://localhost:9090 |
   | Grafana | http://localhost:3002 |

4. **Grafana login**
   - Username: `admin`
   - Password: `admin`

### Running Services Individually

**Auth Service**
```bash
cd auth-service
./mvnw spring-boot:run
```

**Habit Service**
```bash
cd habit-service
./mvnw spring-boot:run
```

**Frontend**
```bash
cd frontend
npm install
npm run dev
```

---

## API Reference

### Auth Service (Port 8081)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/register` | Register new user |
| POST | `/api/auth/login` | Login and receive JWT |
| GET | `/api/auth/validate` | Validate JWT token |

**Register**
```bash
curl -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "password123"}'
```

**Login**
```bash
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "password123"}'
```

### Habit Service (Port 8080)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/habits` | Get all habits for user |
| GET | `/api/habits/{id}` | Get specific habit |
| POST | `/api/habits` | Create new habit |
| PUT | `/api/habits/{id}` | Update habit |
| DELETE | `/api/habits/{id}` | Delete habit |
| POST | `/api/habits/{id}/toggle` | Toggle completion |

**Create Habit**
```bash
curl -X POST http://localhost:8080/api/habits \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"name": "Exercise", "description": "Daily workout", "frequency": "DAILY"}'
```

**Toggle Completion**
```bash
curl -X POST http://localhost:8080/api/habits/1/toggle \
  -H "Authorization: Bearer <token>"
```

---

## Project Structure

```
habitflow/
├── auth-service/
│   ├── src/main/java/com/habitflow/auth/
│   │   ├── config/
│   │   │   ├── SecurityConfig.java
│   │   │   ├── KafkaConfig.java
│   │   │   └── MetricsConfig.java
│   │   ├── controller/
│   │   │   └── AuthController.java
│   │   ├── service/
│   │   │   └── AuthService.java
│   │   ├── repository/
│   │   │   └── UserRepository.java
│   │   ├── model/
│   │   │   └── User.java
│   │   ├── dto/
│   │   │   ├── LoginRequest.java
│   │   │   ├── RegisterRequest.java
│   │   │   └── AuthResponse.java
│   │   ├── event/
│   │   │   ├── UserRegisteredEvent.java
│   │   │   └── KafkaProducer.java
│   │   └── security/
│   │       ├── JwtService.java
│   │       └── JwtAuthenticationFilter.java
│   ├── src/main/resources/
│   │   └── application.yml
│   ├── Dockerfile
│   └── pom.xml
│
├── habit-service/
│   ├── src/main/java/com/habitflow/habits/
│   │   ├── config/
│   │   │   ├── SecurityConfig.java
│   │   │   ├── KafkaConfig.java
│   │   │   └── MetricsConfig.java
│   │   ├── controller/
│   │   │   └── HabitController.java
│   │   ├── service/
│   │   │   └── HabitService.java
│   │   ├── repository/
│   │   │   ├── HabitRepository.java
│   │   │   └── CompletionRepository.java
│   │   ├── model/
│   │   │   ├── Habit.java
│   │   │   └── Completion.java
│   │   ├── dto/
│   │   │   ├── HabitRequest.java
│   │   │   └── HabitResponse.java
│   │   └── event/
│   │       ├── HabitCreatedEvent.java
│   │       ├── HabitCompletedEvent.java
│   │       ├── UserRegisteredEvent.java
│   │       ├── KafkaProducer.java
│   │       └── KafkaConsumer.java
│   ├── src/main/resources/
│   │   └── application.yml
│   ├── Dockerfile
│   └── pom.xml
│
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── store/
│   │   │   ├── store.js
│   │   │   ├── authSlice.js
│   │   │   └── habitsSlice.js
│   │   ├── App.jsx
│   │   └── main.jsx
│   ├── package.json
│   └── vite.config.js
│
├── monitoring/
│   ├── prometheus/
│   │   └── prometheus.yml
│   └── grafana/
│       ├── provisioning/
│       │   ├── datasources/
│       │   │   └── datasources.yml
│       │   └── dashboards/
│       │       └── dashboards.yml
│       └── dashboards/
│           └── habitflow-dashboard.json
│
├── .github/
│   └── workflows/
│       └── ci.yml
│
├── docker-compose.yml
└── README.md
```

---

## Docker Compose Services

| Service | Image | Ports |
|---------|-------|-------|
| postgres | postgres:15-alpine | 5432 |
| zookeeper | confluentinc/cp-zookeeper:7.5.0 | 2181 |
| kafka | confluentinc/cp-kafka:7.5.0 | 9092, 29092 |
| auth-service | Built from ./auth-service | 8081 |
| habit-service | Built from ./habit-service | 8080 |
| prometheus | prom/prometheus:v2.47.0 | 9090 |
| grafana | grafana/grafana:10.0.0 | 3002 |

---

## Testing

### Backend Tests
```bash
# Auth Service
cd auth-service
./mvnw test

# Habit Service
cd habit-service
./mvnw test
```

### Frontend Tests
```bash
cd frontend
npm run test
```

### All Tests (CI)
```bash
# Runs automatically on pull requests via GitHub Actions
```

---

## CI/CD Pipeline

GitHub Actions workflow runs on every pull request:

1. **Build** - Compile Java services
2. **Test** - Run JUnit tests (backend) and Vitest (frontend)
3. **Docker Build** - Verify containers build successfully
4. **Deploy** - Auto-deploy to Railway/Vercel on merge to main

---

## Environment Variables

### Auth Service
| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | PostgreSQL connection URL | - |
| `SPRING_DATASOURCE_USERNAME` | Database username | - |
| `SPRING_DATASOURCE_PASSWORD` | Database password | - |
| `JWT_SECRET` | Secret key for JWT signing | - |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address | kafka:29092 |

### Habit Service
| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | PostgreSQL connection URL | - |
| `SPRING_DATASOURCE_USERNAME` | Database username | - |
| `SPRING_DATASOURCE_PASSWORD` | Database password | - |
| `AUTH_SERVICE_URL` | Auth service URL for validation | http://auth-service:8081 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address | kafka:29092 |

---

## Troubleshooting

### Kafka Consumer Deserialization Error
If you see infinite error loops about deserialization:
```bash
# Clear Kafka data and restart
docker compose down -v
docker compose up --build
```

### Docker Credential Error
If you see `docker-credential-desktop` errors:
```bash
# Remove or edit ~/.docker/config.json
rm ~/.docker/config.json
```

### Port Already in Use
```bash
# Find and kill process on port
lsof -i :8080
kill -9 <PID>
```

---

## License

MIT

---

## Author

**Mohamed Ahmed**

- LinkedIn: [linkedin.com/in/yourprofile](https://www.linkedin.com/in/mohamed-ahmed-0998041b3/)
- GitHub: [github.com/yourusername](https://github.com/Amohamed24)

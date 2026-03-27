# Docker And AWS Deployment

## What This Covers

This setup containers the Spring Boot backend only. The `frontend` directory is an Expo application and should be
deployed separately from the backend API.

For the full-stack Cloudflare Pages + cheap backend VM path,
see [Documents/RunbooksIndex.md](C:\Projects\Dalili health\dalili\Documents\RunbooksIndex.md).

## Local Docker Run

Build and start the backend plus PostgreSQL:

```powershell
.\gradlew.bat bootJar
docker compose up --build
```

The API will be available on `http://localhost:8181` and the database on port `5433`.

## Required Environment Variables For AWS

Set these in your ECS task definition, App Runner service, or Elastic Beanstalk environment:

```text
PORT=8181
DALILI_STORAGE_DIR=/home/spring/.dalili
DALILI_AUDIT_DEVICE_ID=<optional-stable-device-id>
SPRING_DATASOURCE_URL=jdbc:postgresql://<rds-endpoint>:5432/dalili
SPRING_DATASOURCE_USERNAME=<db-user>
SPRING_DATASOURCE_PASSWORD=<db-password>
DALILI_JWT_SECRET=<long-random-secret>
DALILI_SECURITY_ALLOWED_ORIGINS=https://<your-frontend-domain>
GROQ_API_KEY=<optional>
```

For AWS, use Amazon RDS PostgreSQL instead of running the `db` container in production.
If you do not mount persistent storage for the app, set `DALILI_AUDIT_DEVICE_ID` so the service identity stays stable
across task replacements.

## Build And Push To Amazon ECR

Replace the placeholders with your AWS account, region, and repository name:

```powershell
.\gradlew.bat bootJar
aws ecr create-repository --repository-name dalili-backend
aws ecr get-login-password --region <region> | docker login --username AWS --password-stdin <account-id>.dkr.ecr.<region>.amazonaws.com
docker build -t dalili-backend .
docker tag dalili-backend:latest <account-id>.dkr.ecr.<region>.amazonaws.com/dalili-backend:latest
docker push <account-id>.dkr.ecr.<region>.amazonaws.com/dalili-backend:latest
```

## Deploy On AWS

Recommended target: ECS Fargate.

1. Create an RDS PostgreSQL instance and note the JDBC URL.
2. Create an ECS task definition that uses the pushed ECR image.
3. Add the environment variables listed above.
4. Expose container port `8181`.
5. Configure the load balancer health check path as `/health`.
6. Store secrets such as `SPRING_DATASOURCE_PASSWORD`, `DALILI_JWT_SECRET`, and `GROQ_API_KEY` in AWS Secrets Manager or
   SSM Parameter Store instead of plain text.

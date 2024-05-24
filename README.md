# Project README

## Project Overview

This project offers APIs for managing retention models and policies. It uses Spring Boot for the backend, H2 as the database, and Apache Camel for routing and mediation.

## Prerequisites

Before running the application, ensure you have the following installed:

- Docker
- Maven
- JDK (Java Development Kit)

## How to Run

### Step 1: Set Up the Database

Navigate to the `/database` folder and initialize the database by executing the following command:

```sh
docker compose up
```

### Step 2: Build and Run the Application

You have two options to run the application: via Maven or by running the JAR file directly.

#### Option 1: Using Maven

Navigate to the `openapi-contract-first` folder and execute the following commands to build and run the application:

```sh
mvn clean install
mvn spring-boot:run
```

The application will be accessible at `http://localhost:8080`.

#### Option 2: Using the JAR File

Alternatively, you can build the JAR file and then run it. Navigate to the `openapi-contract-first` folder and run:

```sh
mvn clean install
```

After the JAR file is built, go to the `openapi-contract-first/target` directory and run:

```sh
java -jar data-retention-policies-service-4.7.0-SNAPSHOT.jar
```

Again, the application will be accessible at `http://localhost:8080`.

## API Documentation

API documentation is available in the `openapi-contract-first/src/main/resources` folder.

- `retentionModel.yml` documents the Retention Model API using the OpenAPI specification.
- `retentionPolicy.yml` documents the Retention Policy API using the OpenAPI specification.

## Testing

You can test the APIs using tools like Postman or any other application that supports OpenAPI specifications.

## Additional Notes

Ensure that when testing, your requests are directed to `http://localhost:8080`, as the application runs on port 8080.

## Design Decisions

### Enriching the Retention Model and Retention Policy

To ensure accountability of actions, the `RetentionModel` and  `RetentionPolicy` includes additional fields:
- `created_by`
- `created_at`
- `deleted_by`
- `deleted_at`

For soft delete functionality, an `updated_to_id` field is implemented, referencing the updated row for traceability.
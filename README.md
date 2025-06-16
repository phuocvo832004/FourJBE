# FourJBE Microservices Backend Setup

This guide explains how to set up and deploy the FourJBE microservices backend system using Docker Compose. The system includes multiple microservices, API Gateway (Kong), service discovery (Consul), message queues (RabbitMQ, Kafka), and other supporting services.

## Overview
The system consists of:
- **Microservices**: User, Product, Cart, Order, IAM, Search services
- **API Gateway**: Kong for routing and authentication
- **Service Discovery**: Consul for service registration and discovery
- **Message Queues**: RabbitMQ and Kafka for asynchronous communication
- **Databases**: PostgreSQL, Elasticsearch, Redis
- **Monitoring**: Health checks and logging

## Prerequisites
- **Docker**: Latest version installed
- **Docker Compose**: Latest version installed
- **Git**: For cloning the repository
- **Java 17**: For local development (optional)
- **Maven 3.x**: For local development (optional)

## Installation
1. **Install Docker**:
   - Download from [Docker Official Site](https://www.docker.com/products/docker-desktop)
   - Verify installation:
     ```bash
     docker --version
     docker compose version
     ```

## Project Setup
1. **Clone the Repository**:
   ```bash
   git clone https://github.com/your-org/FourJBE.git
   cd FourJBE/backend
   ```

2. **Directory Structure**:
   ```
   backend/
   ├── services/
   │   ├── user-service/
   │   ├── product-service/
   │   ├── cart-service/
   │   ├── order-service/
   │   ├── iam-service/
   │   └── search-service/
   ├── docker/
   │   ├── docker-compose.yml
   │   └── services/
   └── Jenkinsfile
   ```

3. **Environment Configuration**:
   - Create `.env` file in `backend/docker`:
     ```env
     KONG_DB_PASSWORD=
     AUTH0_DOMAIN=
     AUTH0_AUDIENCE=http://localhost:80
     USER_SERVICE_PORT=8083
     PRODUCT_SERVICE_PORT=8084
     CART_SERVICE_PORT=8085
     ORDER_SERVICE_PORT=8086
     SEARCH_SERVICE_PORT=8087
     DOCKER_HUB_USERNAME=
     ```
## CI/CD Pipeline Setup

### Prerequisites for CI/CD
- **Jenkins Server**: Latest LTS version
- **Java 17**: For Jenkins agent
- **Maven 3.x**: For building services
- **Docker**: For containerization
- **SonarQube**: For code quality analysis
- **Trivy**: For container security scanning

### Jenkins Environment Setup
1. **Install Required Jenkins Plugins**:
   - Docker Pipeline
   - SonarQube Scanner
   - Git Integration
   - Pipeline: GitHub
   - Credentials Binding

2. **Configure Jenkins Credentials**:
   - Add Docker Hub credentials:
     ```
     ID: docker-hub-credentials
     Username: <your-dockerhub-username>
     Password: <your-dockerhub-password>
     ```
   - Add SonarQube token:
     ```
     ID: SONAR_TOKEN
     Token: <your-sonarqube-token>
     ```

3. **Configure Jenkins Tools**:
   - Add Maven installation:
     ```
     Name: Maven3
     Version: 3.x
     ```
   - Add SonarQube server:
     ```
     Name: SonarQubeServer
     Server URL: <your-sonarqube-url>
     ```

### Pipeline Configuration
1. **Create New Pipeline**:
   - Go to Jenkins Dashboard
   - Click "New Item"
   - Enter pipeline name (e.g., "FourJBE-Backend")
   - Select "Pipeline"
   - Click "OK"

2. **Configure Pipeline**:
   - In Pipeline configuration:
     - Definition: Pipeline script from SCM
     - SCM: Git
     - Repository URL: <your-repo-url>
     - Branch: */main
     - Script Path: backend/Jenkinsfile

### Running the Pipeline
1. **Manual Trigger**:
   - Go to pipeline page
   - Click "Build Now"

2. **Automatic Trigger**:
   - Configure webhook in repository
   - Pipeline will trigger on push to main branch

### Pipeline Stages
The pipeline executes the following stages:

1. **Discover Microservices**:
   - Scans `backend/services/` directory
   - Identifies all microservices
   - Sets environment variables

2. **Build and Test**:
   - Builds each microservice using Maven
   - Runs unit tests
   - Generates test reports

3. **Build and Push Docker Images**:
   - Builds Docker images for each service
   - Pushes images to Docker Hub
   - Tags images with version and build number

4. **Prepare Docker Networks**:
   - Creates required Docker networks
   - Ensures network connectivity

5. **Deploy to Docker**:
   - Deploys all microservices
   - Configures environment variables
   - Sets up service dependencies

6. **SonarQube Analysis**:
   - Performs code quality analysis
   - Generates quality reports
   - Enforces quality gates

7. **Security Scan**:
   - Scans Docker images using Trivy
   - Identifies security vulnerabilities
   - Generates security reports

### Monitoring Pipeline Execution
1. **View Pipeline Status**:
   - Go to pipeline page
   - Click on build number
   - View stage execution status

2. **Check Build Logs**:
   - Click on any stage
   - View detailed execution logs
   - Download build artifacts

3. **View Test Results**:
   - Go to "Test Results" section
   - View test reports
   - Check test coverage

4. **Access Quality Reports**:
   - Go to SonarQube dashboard
   - View code quality metrics
   - Check security vulnerabilities

### Troubleshooting CI/CD
1. **Build Failures**:
   - Check Maven build logs
   - Verify dependencies
   - Check Java version compatibility

2. **Docker Issues**:
   - Verify Docker daemon is running
   - Check Docker Hub credentials
   - Ensure sufficient disk space

3. **SonarQube Problems**:
   - Verify SonarQube server is running
   - Check token permissions
   - Validate project configuration

4. **Deployment Issues**:
   - Check Docker network configuration
   - Verify environment variables
   - Check service dependencies

### Best Practices for CI/CD
1. **Code Quality**:
   - Maintain high test coverage
   - Follow coding standards
   - Regular code reviews

2. **Security**:
   - Regular security scans
   - Update dependencies
   - Secure credentials

3. **Monitoring**:
   - Monitor pipeline performance
   - Track build times
   - Analyze failure patterns

4. **Maintenance**:
   - Regular pipeline updates
   - Clean up old builds
   - Update Jenkins plugins

For detailed documentation, refer to:
- [Jenkins Documentation](https://www.jenkins.io/doc/)
- [SonarQube Documentation](https://docs.sonarqube.org/)
- [Trivy Documentation](https://aquasecurity.github.io/trivy/)
- [Docker Documentation](https://docs.docker.com)
- [Kong Documentation](https://docs.konghq.com)
- [Consul Documentation](https://www.consul.io/docs)

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

## Deployment
1. **Start the System**:
   ```bash
   cd docker
   docker compose up --build
   ```

2. **Monitor Deployment**:
   - Check container status:
     ```bash
     docker ps
     ```
   - View service logs:
     ```bash
     docker logs <service-name>
     ```

3. **Access Services**:
   - API Gateway: http://localhost:8000
   - Consul UI: http://localhost:8500
   - RabbitMQ UI: http://localhost:15672 (guest/guest)
   - Elasticsearch: http://localhost:9200
   - Konga: http://localhost:1337

4. **Verify Health**:
   ```bash
   curl http://localhost:8083/actuator/health
   ```

## Development (Optional)
1. **Build Individual Services**:
   ```bash
   cd services/<service-name>
   mvn clean install
   ```

2. **Update Service**:
   - Modify code
   - Rebuild service
   - Restart container:
     ```bash
     docker compose restart <service-name>
     ```

## Maintenance
1. **Stop the System**:
   ```bash
   docker compose down
   ```

2. **Clean Up**:
   ```bash
   docker compose down -v
   ```

## Troubleshooting
- **Port Conflicts**:
  - Ensure ports 8082-8087, 8000, 8500, 15672, 9200 are available
  - Check running containers: `docker ps`

- **Service Startup Issues**:
  - Check logs: `docker logs <service-name>`
  - Verify environment variables in `.env`
  - Ensure all required services are running

- **Connection Problems**:
  - Verify network connectivity between services
  - Check Consul service registration
  - Validate Kong routes configuration

## Best Practices
- **Security**: 
  - Change default passwords
  - Use secure environment variables
  - Enable authentication for all services

- **Monitoring**:
  - Enable health checks
  - Set up logging
  - Monitor service metrics

- **Backup**:
  - Regularly backup databases
  - Maintain service configurations
  - Version control all changes

For detailed documentation, refer to:
- [Docker Documentation](https://docs.docker.com)
- [Kong Documentation](https://docs.konghq.com)
- [Consul Documentation](https://www.consul.io/docs)

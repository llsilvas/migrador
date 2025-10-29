# Migrador RG Batch - Arquitetura Multi-Módulo

Sistema de migração em lote de dados biométricos com arquitetura Control/Data Plane.

## Arquitetura

### Módulos

- **migrador-core**: Domain models, DTOs, enums e mappers compartilhados
- **migrador-infrastructure**: Configurações de Redis, SQL Server, ElasticSearch
- **migrador-batch-control**: Control Plane - coordenação, particionamento, métricas
- **migrador-batch-workers**: Data Plane - readers, processors, writers
- **migrador-app**: Aplicação Spring Boot principal

### Tecnologias

- Java 21
- Spring Boot 3.5.7
- Redis (JobRepository + Cache + DLQ)
- MS SQL Server (origem)
- ElasticSearch 7.17.18 (destino)

## Desenvolvimento

### Build

```bash
# Build completo
./mvnw clean package

# Build apenas da aplicação (inclui dependências)
cd migrador-app
../mvnw clean package -pl migrador-app -am
```

### Executar

```bash
# Via Maven
./mvnw spring-boot:run -pl migrador-app

# Via JAR
java -jar migrador-app/target/migrador-app-1.0.0.jar
```

### Docker Compose (Redis)

```bash
docker-compose up -d redis
```

## API Endpoints

- `POST /migrador-rg/api/migration/start?partitions=100&threads=32` - Iniciar migração
- `POST /migrador-rg/api/migration/stop/{idRequisicao}` - Parar migração
- `GET /migrador-rg/api/migration/dlq/count` - Contador DLQ
- `GET /migrador-rg/api/migration/dlq/records?limit=100` - Registros com falha

## Estrutura de Diretórios

```
migrador-iirgd/
├── pom.xml                          # Parent POM
├── migrador-core/                   # Domain + DTOs
├── migrador-infrastructure/         # Configs + Repositories
├── migrador-batch-control/          # Control Plane
├── migrador-batch-workers/          # Data Plane
└── migrador-app/                    # Spring Boot App
```

## Configuração

Ver `migrador-app/src/main/resources/application.yml`

## Documentação Completa

Ver `MIGRATION_ARCHITECTURE_REDIS_STRUCTURE.md`

# SQL Server Setup - Ambiente de Testes

## Objetivo

Este guia explica como configurar o ambiente de testes com **SQL Server** para validar a implementação do `JdbcPagingItemReader` antes de conectar ao banco IIRGD real.

---

## Quick Start

### 1. Setup Automático (Recomendado)

```bash
# Subir ambiente completo (Redis + Elasticsearch + SQL Server + popular dados)
./setup-environment.sh
```

**O que este script faz:**
1. Sobe containers Docker (Redis, Elasticsearch, SQL Server)
2. Aguarda SQL Server estar pronto (~30-60s)
3. Cria database `IIRGD_TEST`
4. Cria tabela `TB_COLETA`
5. Popula **100.000 registros** de teste
6. Verifica serviços

**Tempo estimado:** 2-3 minutos

---

### 2. Build e Execução

```bash
# Build do projeto
mvn clean install

# Executar migração
mvn spring-boot:run -pl migrador-app
```

---

## Setup Manual (Alternativa)

### 1. Subir Apenas SQL Server

```bash
# Subir apenas SQL Server
docker-compose up -d sqlserver

# Aguardar estar pronto
docker exec migrador-sqlserver /opt/mssql-tools18/bin/sqlcmd \
    -S localhost -U sa -P "Migrador@2025!Strong" -C -Q "SELECT 1"
```

### 2. Executar Scripts SQL

```bash
# Criar database
docker exec -i migrador-sqlserver /opt/mssql-tools18/bin/sqlcmd \
    -S localhost -U sa -P "Migrador@2025!Strong" -C \
    < sql-scripts/01-init-database.sql

# Popular dados
docker exec -i migrador-sqlserver /opt/mssql-tools18/bin/sqlcmd \
    -S localhost -U sa -P "Migrador@2025!Strong" -C \
    < sql-scripts/02-populate-test-data.sql
```

---

## Estrutura do Banco de Dados

### Database: `IIRGD_TEST`

### Tabela: `TB_COLETA`

```sql
CREATE TABLE [dbo].[TB_COLETA] (
    [ID] BIGINT IDENTITY(1,1) PRIMARY KEY,      -- Auto-incremento
    [ID_COLETA_VALID] UNIQUEIDENTIFIER NOT NULL, -- UUID único
    [CPF] VARCHAR(11),                           -- CPF (pode ser nulo)
    [RG] VARCHAR(20),                            -- RG
    [DATA_COLETA] DATETIME2 NOT NULL,            -- Timestamp da coleta
    [TIPO_COLETA] VARCHAR(20) NOT NULL,          -- DIGITAL, PRESENCIAL, etc
    [IMAGEM_DIGITAL] VARBINARY(MAX),             -- Imagem (NULL em testes)
    [CREATED_AT] DATETIME2 NOT NULL,
    [UPDATED_AT] DATETIME2
);
```

### Índices

```sql
-- Primário
CONSTRAINT UK_ID_COLETA_VALID UNIQUE (ID_COLETA_VALID)

-- Range queries (particionamento)
CREATE NONCLUSTERED INDEX IX_TB_COLETA_ID ON TB_COLETA (ID ASC);

-- Busca por CPF
CREATE NONCLUSTERED INDEX IX_TB_COLETA_CPF ON TB_COLETA (CPF ASC)
    WHERE CPF IS NOT NULL;
```

---

## Dados de Teste

### Estatísticas

```
Total de registros: 100.000
Range de IDs:       1 - 100.000
CPFs únicos:        ~90.000 (alguns nulos)
RGs únicos:         100.000
Data coleta:        Últimos 365 dias
Tipos:              DIGITAL (33%), PRESENCIAL (33%), AUTOMATICO (33%)
```

### Distribuição de Partições

Com **50 partições** (config padrão):

```
Partition 0:  ID 1       → 2.000   (2.000 registros)
Partition 1:  ID 2.001   → 4.000   (2.000 registros)
...
Partition 49: ID 98.001  → 100.000 (2.000 registros)
```

---

## Conexão ao Banco

### Via Spring Boot (application.yml)

```yaml
iirgd:
  datasource:
    jdbc-url: jdbc:sqlserver://localhost:1433;databaseName=IIRGD_TEST;encrypt=false;trustServerCertificate=true
    username: sa
    password: Migrador@2025!Strong
    driver-class-name: com.microsoft.sqlserver.jdbc.SQLServerDriver
    hikari:
      maximum-pool-size: 60
      minimum-idle: 20
```

### Via SQL Client (DBeaver, Azure Data Studio, etc)

```
Host:       localhost
Port:       1433
Database:   IIRGD_TEST
User:       sa
Password:   Migrador@2025!Strong
```

### Via Docker (sqlcmd)

```bash
# Conectar ao SQL Server
docker exec -it migrador-sqlserver /opt/mssql-tools18/bin/sqlcmd \
    -S localhost -U sa -P "Migrador@2025!Strong" -C

# Executar query
1> USE IIRGD_TEST
2> GO
1> SELECT COUNT(*) FROM TB_COLETA
2> GO
```

---

## Verificação e Troubleshooting

### Verificar Status dos Containers

```bash
docker-compose ps
```

**Esperado:**
```
NAME                    STATUS
migrador-redis          Up (healthy)
migrador-elasticsearch  Up (healthy)
migrador-sqlserver      Up (healthy)
```

### Verificar Dados

```bash
# Quantidade de registros
docker exec migrador-sqlserver /opt/mssql-tools18/bin/sqlcmd \
    -S localhost -U sa -P "Migrador@2025!Strong" -C \
    -d IIRGD_TEST \
    -Q "SELECT COUNT(*) FROM TB_COLETA"
```

**Esperado:** `100000` ou `100.000` dependendo da localização

### Logs do SQL Server

```bash
docker logs migrador-sqlserver -f
```

### Recriar Dados

```bash
# Dropar dados existentes
docker exec -i migrador-sqlserver /opt/mssql-tools18/bin/sqlcmd \
    -S localhost -U sa -P "Migrador@2025!Strong" -C \
    -Q "USE IIRGD_TEST; DELETE FROM TB_COLETA"

# Repopular
docker exec -i migrador-sqlserver /opt/mssql-tools18/bin/sqlcmd \
    -S localhost -U sa -P "Migrador@2025!Strong" -C \
    < sql-scripts/02-populate-test-data.sql
```

---

## Performance Esperada

### Leitura (JdbcPagingItemReader)

```
Configuração:
- Partições:   50
- Chunk size:  1000
- Pool size:   60 conexões

Performance esperada:
- Throughput:  ~10.000-15.000 registros/s
- Tempo (100k): ~7-10 segundos
```

**Fatores:**
- I/O do disco (SSD vs HDD)
- Latência de rede (localhost = mínima)
- CPU disponível

---

## Limpeza

### Parar Containers

```bash
docker-compose down
```

### Remover Dados

```bash
# Remover containers E volumes (dados perdidos)
docker-compose down -v

# Remover apenas volumes
docker volume rm migrador-iirgd_sqlserver_data
```

---

## Próximos Passos

1. **Validar com 100k registros** (testes locais)
2. **Aumentar para 1M** (editar script SQL ou multiplicar partições)
3. **Conectar ao IIRGD real** (produção)

### Para conectar ao IIRGD real:

Editar `application.yml`:

```yaml
iirgd:
  datasource:
    jdbc-url: jdbc:sqlserver://IIRGD-HOST:1433;databaseName=IIRGD_PROD
    username: ${IIRGD_USER}
    password: ${IIRGD_PASSWORD}
```

---

## Referências

- [SQL Server Docker](https://hub.docker.com/_/microsoft-mssql-server)
- [Spring Batch JdbcPagingItemReader](https://docs.spring.io/spring-batch/docs/current/reference/html/readersAndWriters.html#jdbcPagingItemReader)
- [Hikari CP Configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)

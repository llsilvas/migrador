#!/bin/bash
# ============================================
# Setup completo do ambiente de desenvolvimento
# ============================================

set -e

echo "============================================"
echo "Migrador IIRGD - Environment Setup"
echo "============================================"
echo ""

# Cores para output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 1. Subir containers Docker
echo -e "${YELLOW}[1/5] Starting Docker containers...${NC}"
docker-compose up -d

# 2. Aguardar SQL Server estar pronto
echo ""
echo -e "${YELLOW}[2/5] Waiting for SQL Server to be ready...${NC}"
echo "This may take 30-60 seconds on first startup..."

MAX_ATTEMPTS=30
ATTEMPT=0

while [ $ATTEMPT -lt $MAX_ATTEMPTS ]; do
    if docker exec migrador-sqlserver /opt/mssql-tools18/bin/sqlcmd \
        -S localhost -U sa -P "Migrador@2025!Strong" -C \
        -Q "SELECT 1" >/dev/null 2>&1; then
        echo -e "${GREEN}✓ SQL Server is ready!${NC}"
        break
    fi

    ATTEMPT=$((ATTEMPT + 1))
    echo -n "."
    sleep 2
done

if [ $ATTEMPT -eq $MAX_ATTEMPTS ]; then
    echo -e "${RED}✗ Timeout waiting for SQL Server${NC}"
    exit 1
fi

# 3. Inicializar database
echo ""
echo -e "${YELLOW}[3/5] Initializing IIRGD_TEST database...${NC}"

docker exec -i migrador-sqlserver /opt/mssql-tools18/bin/sqlcmd \
    -S localhost -U sa -P "Migrador@2025!Strong" -C \
    -i /docker-entrypoint-initdb.d/01-init-database.sql

echo -e "${GREEN}✓ Database created${NC}"

# 4. Popular dados de teste
echo ""
echo -e "${YELLOW}[4/5] Populating test data (100k records)...${NC}"
echo "This may take 30-60 seconds..."

docker exec -i migrador-sqlserver /opt/mssql-tools18/bin/sqlcmd \
    -S localhost -U sa -P "Migrador@2025!Strong" -C \
    -i /docker-entrypoint-initdb.d/02-populate-test-data.sql

echo -e "${GREEN}✓ Test data populated${NC}"

# 5. Verificar serviços
echo ""
echo -e "${YELLOW}[5/5] Verifying services...${NC}"

# Redis
if docker exec migrador-redis redis-cli ping | grep -q PONG; then
    echo -e "${GREEN}✓ Redis: OK${NC}"
else
    echo -e "${RED}✗ Redis: NOT READY${NC}"
fi

# Elasticsearch
if curl -s http://localhost:9200/_cluster/health | grep -q "status"; then
    echo -e "${GREEN}✓ Elasticsearch: OK${NC}"
else
    echo -e "${YELLOW}⚠ Elasticsearch: Starting... (may take a minute)${NC}"
fi

# SQL Server - verificar contagem de registros
RECORD_COUNT=$(docker exec migrador-sqlserver /opt/mssql-tools18/bin/sqlcmd \
    -S localhost -U sa -P "Migrador@2025!Strong" -C \
    -d IIRGD_TEST \
    -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM TB_COLETA" -h-1 | tr -d ' \r\n')

if [ "$RECORD_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✓ SQL Server: OK ($RECORD_COUNT records in TB_COLETA)${NC}"
else
    echo -e "${RED}✗ SQL Server: No data found${NC}"
    exit 1
fi

# Resumo
echo ""
echo "============================================"
echo -e "${GREEN}Environment setup completed!${NC}"
echo "============================================"
echo ""
echo "Services available:"
echo "  - Redis:          localhost:6379"
echo "  - Elasticsearch:  http://localhost:9200"
echo "  - SQL Server:     localhost:1433"
echo ""
echo "Database connection:"
echo "  - Host:     localhost"
echo "  - Port:     1433"
echo "  - Database: IIRGD_TEST"
echo "  - User:     sa"
echo "  - Password: Migrador@2025!Strong"
echo "  - Records:  $RECORD_COUNT"
echo ""
echo "Next steps:"
echo "  1. Build the project:  mvn clean install"
echo "  2. Run the migrator:   mvn spring-boot:run -pl migrador-app"
echo ""
echo "To stop the environment: docker-compose down"
echo "============================================"

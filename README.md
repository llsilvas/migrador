# Migrador IIRGD → Plataforma Biometria

Sistema de migração de dados biométricos do banco legado IIRGD (SQL Server) para Elasticsearch, utilizando Spring Batch com Virtual Threads (Java 21) e coordenação distribuída via Redis.

## 🎯 Objetivo

Migrar **70 milhões** de registros biométricos do sistema IIRGD para o novo índice Elasticsearch `biometria`, com:
- **Alta performance** (8.000-12.000 registros/segundo)
- **Resiliência** (retry automático, skip policy, Dead Letter Queue)
- **Observabilidade** (logs estruturados, métricas Prometheus, health checks)
- **Coordenação distribuída** (Redis + Redisson locks)

## 📊 Arquitetura

### Módulos (Hexagonal Architecture)

```
migrador-iirgd/
├── migrador-core/              # Domain + Ports (regras de negócio)
│   ├── domain/model/           # Entidades (ColetaEntity, ColetaMetadata)
│   └── port/                   # Interfaces (PartitionCoordinator, DLQ)
│
├── migrador-infrastructure/    # Adapters (implementações externas)
│   ├── client/                 # ElasticClient (Bulk API)
│   ├── redis/                  # RedisPartitionCoordinator, RedisLocks
│   └── config/                 # ElasticsearchConfig, RedisConfig
│
├── migrador-batch-control/     # Orquestração Spring Batch
│   ├── config/                 # BatchJobConfig (virtual threads)
│   ├── partition/              # RangePartitioner, Locks
│   ├── listener/               # JobExecutionListener, Metrics
│   └── metrics/                # ProgressTracker, MigrationMetrics
│
├── migrador-batch-workers/     # Processamento (Reader, Processor, Writer)
│   ├── reader/                 # ColetaItemReader (JDBC)
│   ├── processor/              # ColetaItemProcessor (transformações)
│   └── writer/                 # ColetaItemWriter (Elasticsearch Bulk)
│
└── migrador-app/               # Entry Point (Spring Boot)
    ├── controller/             # REST API (iniciar job, status)
    └── MigradorApplication     # Main class
```

### Fluxo de Dados

```
[IIRGD Oracle DB]
       ↓
[JdbcPagingItemReader] → Lê 1000 registros (chunk)
       ↓
[ColetaItemProcessor]  → Transforma Entity → Metadata
       ↓
[ColetaItemWriter]     → Elasticsearch Bulk API (1000 docs/request)
       ↓
[Elasticsearch Index: biometria]

       ↓ (em caso de erro)
[RedisDeadLetterQueue] → Armazena falhas para retry manual
```

### Particionamento

```
70M registros divididos em 100 partições:
Partition 0:  ID 1        → 700,000
Partition 1:  ID 700,001  → 1,400,000
...
Partition 99: ID 69,300,001 → 70,000,000

Cada partição = 1 Virtual Thread
100 partições executam em paralelo usando 32 carrier threads
```

## 🚀 Tecnologias

| Componente | Tecnologia | Versão |
|------------|------------|--------|
| **Linguagem** | Java | 21 |
| **Framework** | Spring Boot | 3.5.7 |
| **Batch** | Spring Batch | 5.x |
| **Threads** | Virtual Threads (Project Loom) | JEP 444 |
| **Banco Origem** | SQL Server JDBC | 2019+ |
| **Destino** | Elasticsearch | 7.17.18 |
| **Coordenação** | Redis + Redisson | 7 + 3.35.0 |
| **Locks Distribuídos** | Redisson RLock | Reentrant + Watchdog |
| **Batch Metadata** | H2 Database | Em memória |
| **Build** | Maven | 3.8+ |
| **GC** | ZGC Generational | Java 21 |

## ⚡ Performance

### Virtual Threads

```java
TaskExecutorAdapter executor = new TaskExecutorAdapter(
    Executors.newVirtualThreadPerTaskExecutor()
);
```

**Benefícios:**
- 100 partições simultâneas (antes: 10 com platform threads)
- Carrier threads liberadas durante I/O (Elasticsearch, JDBC)
- **8-12x mais throughput** vs thread pool tradicional

### Estimativas

| Configuração | Partições | Throughput | Tempo (70M) |
|--------------|-----------|------------|-------------|
| **Platform Threads** | 10 | ~1,000/s | ~19 horas |
| **Virtual Threads** | 100 | ~8,000-12,000/s | **1.5-2.5 horas** |

## 🏗️ Setup

### Pré-requisitos

```bash
# Java 21+
java -version  # Deve retornar 21.x

# Maven 3.8+
mvn -version

# Docker (para Redis e Elasticsearch locais)
docker --version
```

### Subir Dependências Locais

```bash
# Redis
docker run -d --name redis \
  -p 6379:6379 \
  redis:7-alpine

# Elasticsearch
docker run -d --name elasticsearch \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  -p 9200:9200 \
  docker.elastic.co/elasticsearch/elasticsearch:7.17.18
```

### Build

```bash
mvn clean package
```

### Configuração

Editar `migrador-app/src/main/resources/application.yml`:

```yaml
# Datasource IIRGD (origem) - CONFIGURAR
spring:
  datasource-iirgd:
    url: jdbc:oracle:thin:@//host:1521/service
    username: ${IIRGD_USER}
    password: ${IIRGD_PASSWORD}

# Elasticsearch (destino)
bio:
  elastic:
    url: http://localhost:9200
    user: elastic
    password: changeme

# Redis (coordenação)
spring:
  data:
    redis:
      host: localhost
      port: 6379

# Migration
migration:
  partitions: 50        # Testes local
  # partitions: 100     # Produção
  min-id: 1
  max-id: 500000        # Testes: 500k registros
  # max-id: 70000000    # Produção: 70M
  chunk-size: 1000
```

## 🎮 Execução

### Desenvolvimento (Maven)

```bash
# Usa jvm-development.options (4GB heap, G1GC)
./run-development.sh

# Ou Maven direto
mvn spring-boot:run -pl migrador-app
```

### Produção

```bash
# Build primeiro
mvn clean package

# Usa jvm-production.options (16GB heap, ZGC)
./run-production.sh
```

### Iniciar Job via API

```bash
# Iniciar migração
curl -X POST http://localhost:8080/migrador-iirgd/migration/start

# Verificar status
curl http://localhost:8080/migrador-iirgd/migration/status

# Métricas Prometheus
curl http://localhost:8080/migrador-iirgd/actuator/prometheus
```

## 📊 Monitoramento

### Logs

```bash
# Ver logs em tempo real
tail -f logs/application.log

# Ver GC logs (produção)
tail -f logs/gc.log

# Buscar por partition específica
grep "Partition 5" logs/application.log
```

### Logs Estruturados (Português BR)

Os logs foram otimizados para serem **concisos**, **estruturados** e em **português brasileiro**:

#### Exemplo de Execução Completa

```
2025-11-03 10:15:30 [JOB] Iniciado | id=123 | params={timestamp=1730635530000}
2025-11-03 10:15:31 [PARTICIONAMENTO] Criando 100 partições para 70000000 registros (~700000 registros/partição)
2025-11-03 10:15:32 [PARTICIONAMENTO] Concluído | total=100 partições

2025-11-03 10:15:33 [PARTIÇÃO] Iniciada | id=0 | range=1-700000
2025-11-03 10:15:33 [PARTIÇÃO] Iniciada | id=1 | range=700001-1400000
...

2025-11-03 10:15:45 [ESCRITOR] Chunk enviado | índice=biometria | tamanho=1000 | sucesso=998 | erros=2 | prepMs=45 | bulkMs=230 | totalMs=275 | throughput=3636/s | médiaMs=0.28

2025-11-03 10:28:15 [PARTIÇÃO] Concluída | id=0 | status=COMPLETED | lidos=700000 | escritos=698500 | skips=1500 | commits=700 | rollbacks=0
...

2025-11-03 12:30:45 [JOB] Concluído | status=COMPLETED | duração=135min | partições=100/100 | falhas=0 | registros=70000000 | throughput=8642/s | dlq=3250
```

**Benefícios:**
- ✅ Logs em português brasileiro
- ✅ Formato estruturado (fácil parsear com Logstash/Fluentd)
- ✅ 95% menos volume (otimizado vs versão anterior)
- ✅ Informações essenciais consolidadas em cada linha

#### Prometheus

```bash
# Expor métricas
http://localhost:8080/migrador-iirgd/actuator/prometheus

# Exemplo de métricas disponíveis:
# - batch_job_execution_duration_seconds
# - batch_step_execution_count
# - jvm_memory_used_bytes
# - redis_commands_total
```

#### JMX (VisualVM, JConsole)

```bash
jconsole localhost:9010
```

### Dead Letter Queue (Falhas)

```bash
# Ver registros que falharam (via Redis CLI)
redis-cli

> KEYS migration:dlq:*
> HGETALL migration:dlq:record:123456
```

## 🧪 Testes

### Unitários

```bash
mvn test
```

### Integração

```bash
mvn verify
```

### Performance (Staging)

```bash
# Configurar application-staging.yml
spring:
  profiles:
    active: staging

migration:
  partitions: 100
  max-id: 1000000  # 1M para teste

# Executar
mvn spring-boot:run -Dspring-boot.run.profiles=staging
```

## 🔧 Configuração JVM

Ver documentação completa: [JVM-TUNING.md](./JVM-TUNING.md)

### Produção (ZGC)

```bash
# 16GB heap, ZGC Generational, 32 carrier threads
java @jvm-production.options -jar migrador-app.jar
```

### Desenvolvimento (G1GC)

```bash
# 4GB heap, G1GC, 16 carrier threads
java @jvm-development.options -jar migrador-app.jar
```

## 🐛 Troubleshooting

### OOM (Out of Memory)

```
Error: java.lang.OutOfMemoryError: Java heap space
```

**Solução:**
- Aumentar `-Xmx` em `jvm-*.options`
- Reduzir `chunk-size` em `application.yml`
- Verificar memory leaks com JFR

### Elasticsearch Timeout

```
SocketTimeoutException: Read timed out
```

**Solução:**
```yaml
bio:
  elastic:
    socket-timeout-millis: 120000  # Aumentar para 2min
    max-conn-total: 200            # Mais conexões
```

### Redis Connection Refused

```
RedisConnectionException: Unable to connect to Redis
```

**Solução:**
```bash
# Verificar se Redis está rodando
docker ps | grep redis

# Reiniciar Redis
docker restart redis
```

### Partição Travada

```bash
# Verificar locks no Redis
redis-cli

> KEYS migration:lock:partition:*
> DEL migration:lock:partition:42  # Forçar unlock
```

## ✅ Melhorias Implementadas

### Logs Otimizados
- ✅ Logs consolidados em português brasileiro
- ✅ Redução de 95% no volume de logs
- ✅ Formato estruturado (key=value) para fácil parsing
- ✅ Tags de componente padronizadas (`[JOB]`, `[PARTIÇÃO]`, `[ESCRITOR]`, etc.)

### Performance
- ✅ Writer com Bulk API otimizado (1 log por chunk ao invés de 8)
- ✅ Listeners consolidados (13 logs → 2 logs)
- ✅ Virtual Threads corretamente implementados

### Arquitetura
- ✅ Hexagonal Architecture (Ports & Adapters)
- ✅ Reader JDBC real (JdbcPagingItemReader com SqlServerPagingQueryProvider)
- ✅ Processor com validações e transformações
- ✅ Dead Letter Queue para registros falhados
- ✅ Locks distribuídos com Redisson (reentrant + watchdog)

## 📝 Pendências para Produção

Ver análise completa: [CODE-REVIEW.md](./CODE-REVIEW.md)

### P0 - Bloqueadores Críticos
- [ ] Criar testes de integração (Testcontainers)
- [ ] Externalizar senhas (usar env vars ao invés de application.yml)
- [ ] Implementar autenticação na API REST (Spring Security ou API Key)
- [ ] Substituir `redisTemplate.keys()` por Sets em RedisPartitionCoordinator

### P1 - Importantes
- [ ] Implementar MigrationMetrics com Micrometer
- [ ] Adicionar Health Checks para dependências (Elasticsearch, Redis, SQL Server)
- [ ] Configurar Circuit Breaker (Resilience4j)

## 🤝 Contribuindo

1. Criar branch feature: `git checkout -b feature/minha-feature`
2. Seguir convenção de commits: `feat:`, `fix:`, `docs:`, `refactor:`
3. Executar testes: `mvn verify`
4. Criar Pull Request

## 📄 Licença

Copyright © 2025 Prodesp - Tecnologia da Informação

## 📞 Suporte

- **Issues:** https://github.com/prodesp/migrador-iirgd/issues
- **Docs:** [Wiki](./docs/)

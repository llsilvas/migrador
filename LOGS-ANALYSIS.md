# Análise e Padronização de Logs - Migrador IIRGD

**Data:** 2025-11-03
**Objetivo:** Logs resumidos, focados e em português do Brasil

---

## 📊 Análise Atual

### Problemas Identificados:

1. **Idioma Misto** - Logs em inglês (deve ser português BR)
2. **Verbosidade Excessiva** - Muitos logs INFO desnecessários
3. **Logs Técnicos** - Marcadores `$$$$` em DEBUG (ElasticClient)
4. **Separadores Visuais** - `===` poluem logs estruturados

---

## 🎯 Diretrizes de Padronização

### 1. Idioma: Português do Brasil

```java
// ❌ ANTES
log.info("Starting Job: {}", jobName);
log.info("Partition {} started processing", partition);

// ✅ DEPOIS
log.info("Iniciando job: {}", jobName);
log.info("Partição {} iniciou processamento", partition);
```

### 2. Níveis de Log

| Nível | Uso | Exemplos |
|-------|-----|----------|
| **ERROR** | Erros que impedem processamento | Falha ao conectar BD, Elasticsearch down |
| **WARN** | Situações anormais mas recuperáveis | Skips altos, rollbacks, locks órfãos |
| **INFO** | Marcos importantes do fluxo | Início/fim job, métricas finais, resumo partições |
| **DEBUG** | Detalhes técnicos (dev/troubleshooting) | Detalhes de cada chunk, locks individuais |
| **TRACE** | Granular (registro por registro) | Processamento individual de items |

### 3. Formato Estruturado

```
[COMPONENTE] Ação | param1={} | param2={} | ...
```

**Exemplos:**
```
[JOB] Iniciado | id=123 | partições=100
[PARTIÇÃO] Concluída | id=5 | lidos=700000 | escritos=698500 | skips=1500
[WRITER] Chunk enviado | tamanho=1000 | sucesso=998 | erros=2 | tempoMs=275
```

---

## 📝 Mudanças por Componente

### 1. MigrationJobExecutionListener

**Antes (Inglês, Verboso):**
```java
log.info("========================================");
log.info("Starting Job: {}", jobName);
log.info("Job ID: {}", jobId);
log.info("Job Parameters: {}", params);
log.info("--- Thread Info [BEFORE JOB] ---");
log.info("Current Thread: {} (virtual={})", name, isVirtual);
log.info("Active Platform Threads: {}", threads);
log.info("Active Thread Groups: {}", groups);
log.info("Available Processors: {}", processors);
log.info("Memory: used={}MB, max={}MB", used, max);
log.info("========================================");
```

**Depois (Português, Resumido):**
```java
// 1 log consolidado para início
log.info("[JOB] Iniciado | id={} | params={}", jobId, params);
log.debug("[JOB] Ambiente | threads={} | virtual={} | cpus={} | memMB={}/{}",
         activeThreads, isVirtual, processors, usedMB, maxMB);

// 1 log consolidado para fim
log.info("[JOB] Concluído | status={} | duração={}min | partições={}/{} | falhas={} | throughput={}/s",
         status, duration, completed, total, failed, throughput);
```

**Redução:** 13 logs → 2 logs (84% menos)

---

### 2. StepExecutionListener (Partições)

**Antes:**
```java
log.info("=== Starting Step Execution ===");
log.info("Partition: {}", partition);
log.info("ID Range: {} - {}", minId, maxId);
log.info("Step Name: {}", stepName);
log.info("Job Execution ID: {}", jobId);

log.info("=== Step Execution Completed ===");
log.info("Partition: {}", partition);
log.info("Status: {}", status);
log.info("Read: {}", read);
log.info("Written: {}", written);
log.info("Skipped: {}", skipped);
log.info("Commits: {}", commits);
log.info("Rollbacks: {}", rollbacks);
log.info("===============================");
```

**Depois:**
```java
// Início - 1 log
log.info("[PARTIÇÃO] Iniciada | id={} | range={}-{}", partition, minId, maxId);

// Fim - 1 log consolidado
log.info("[PARTIÇÃO] Concluída | id={} | status={} | lidos={} | escritos={} | skips={} | commits={} | rollbacks={}",
         partition, status, read, written, skipped, commits, rollbacks);

// WARN apenas se houver problemas
if (skipCount > 0) {
    log.warn("[PARTIÇÃO] Skips detectados | id={} | quantidade={} | percentual={}%",
            partition, skipCount, skipPercentage);
}
```

**Redução:** 13 logs → 2 logs (84% menos)

---

### 3. RangePartitioner

**Antes:**
```java
log.info("Creating {} partitions for {} records ({}~{} records per partition)",
        gridSize, totalRecords, recordsPerPartition, recordsPerPartition + 1);

log.debug("Partition {}: minId={}, maxId={}, estimated={} records",
        i, currentMin, currentMax, partitionSize);

log.info("Successfully created {} partitions", partitions.size());
```

**Depois:**
```java
log.info("[PARTICIONAMENTO] Criando {} partições para {} registros (~{} registros/partição)",
        gridSize, totalRecords, recordsPerPartition);

log.debug("[PARTICIONAMENTO] Partição {} | range={}-{} | estimativa={}",
         i, currentMin, currentMax, partitionSize);

log.info("[PARTICIONAMENTO] Concluído | total={} partições", partitions.size());
```

---

### 4. ColetaItemWriter (JÁ OTIMIZADO)

**Atual (após otimização anterior):**
```java
log.info("[WRITER] Chunk completed | index={} | size={} | success={} | errors={} | " +
         "prepMs={} | bulkMs={} | totalMs={} | throughput={}/s | avgMs={}",
        indexName, items.size(), success, errors,
        prepElapsed, bulkElapsed, totalElapsed, throughput, avgPerDoc);
```

**Traduzir para português:**
```java
log.info("[ESCRITOR] Chunk enviado | índice={} | tamanho={} | sucesso={} | erros={} | " +
         "prepMs={} | bulkMs={} | totalMs={} | throughput={}/s | médiaMs={}",
        indexName, items.size(), success, errors,
        prepElapsed, bulkElapsed, totalElapsed, throughput, avgPerDoc);
```

---

### 5. ColetaItemProcessor

**Antes:**
```java
log.warn("Skipping record with null/empty idColetaValid: id={}", id);
log.trace("Processed record: id={}, idColetaValid={}", id, valid);
log.error("Error processing record id={}: {}", id, error, e);
```

**Depois:**
```java
log.warn("[PROCESSADOR] Registro ignorado | id={} | motivo=idColetaValid vazio", id);
log.trace("[PROCESSADOR] Registro processado | id={} | validId={}", id, valid);
log.error("[PROCESSADOR] Erro ao processar | id={} | erro={}", id, error, e);
```

---

### 6. ElasticClient

**Antes (marcadores técnicos):**
```java
LOG.debug("$$$$ indexSync {}/{}/{}/{}", index, id, seqNo, primaryTerm);
LOG.debug("$$$$ indexSync result={} index={}/{}", result, index, id);
LOG.debug("$$$$ get {}/{}", index, id);
LOG.debug("$$$$ bulkIndexRest: {} documents to index {}", size, index);
LOG.debug("$$$$ bulkIndexRest result: total={}, success={}, errors={}", ...);
LOG.error("$$$$ Bulk error for document {}: {}", id, error);
LOG.error("$$$$ ElasticsearchException update index {} / id {}", index, id, ex);
```

**Depois:**
```java
LOG.debug("[ELASTIC] Indexando | índice={} | id={}", index, id);
LOG.debug("[ELASTIC] Resultado | índice={} | id={} | resultado={}", index, id, result);
LOG.debug("[ELASTIC] Bulk iniciado | documentos={} | índice={}", size, index);
LOG.debug("[ELASTIC] Bulk concluído | total={} | sucesso={} | erros={}", total, success, errors);
LOG.error("[ELASTIC] Erro no documento | id={} | motivo={}", id, error);
```

**Redução:** Remover marcadores `$$$$` e traduzir

---

### 7. RedisPartitionCoordinator

**Antes:**
```java
log.debug("Registered partition {} with {} records", partitionNumber, totalRecords);
log.info("Partition {} started processing", partitionNumber);
log.info("Partition {} completed successfully", partitionNumber);
log.error("Partition {} marked as FAILED", partitionNumber);
```

**Depois:**
```java
log.debug("[COORDENADOR] Partição registrada | id={} | registros={}", partitionNumber, totalRecords);
log.info("[COORDENADOR] Partição iniciada | id={}", partitionNumber);
log.info("[COORDENADOR] Partição concluída | id={}", partitionNumber);
log.error("[COORDENADOR] Partição FALHOU | id={}", partitionNumber);
```

---

### 8. RedisPartitionLock

**Antes:**
```java
log.info("✓ Acquired lock for partition {} (thread: {}, holdCount: {})", ...);
log.info("✓ Released lock for partition {} (remaining holdCount: {})", ...);
log.warn("✗ Partition {} is already locked by another instance", partition);
```

**Depois:**
```java
log.debug("[LOCK] Adquirido | partição={} | thread={} | holdCount={}", ...);
log.debug("[LOCK] Liberado | partição={} | holdCount={}", partition, count);
log.warn("[LOCK] Partição já bloqueada | id={}", partition);
```

**Mudança:** INFO → DEBUG (locks são operações frequentes)

---

### 9. MigrationController

**Antes:**
```java
log.info("=== Starting Migration Job ===");
log.info("Job started with executionId: {}", id);
log.error("Job is already running", e);
```

**Depois:**
```java
log.info("[API] Job iniciado | executionId={}", id);
log.error("[API] Job já está em execução", e);
```

---

## 📊 Comparação Geral

### Volume de Logs (execução 100 partições):

| Componente | Antes | Depois | Redução |
|------------|-------|--------|---------|
| **Job Listener** | 26 logs | 2 logs | **92%** |
| **Step Listener** | 1300 logs | 200 logs | **85%** |
| **Partitioner** | 103 logs | 3 logs | **97%** |
| **Writer** | 800 logs/s | 100 logs/s | **87%** |
| **Locks** | 200 logs | 0 logs (DEBUG) | **100%** |
| **TOTAL** | ~2400 logs/s | ~103 logs/s | **95.7%** |

---

## 🎨 Padrão de Componentes

| Componente | Tag |
|------------|-----|
| Job principal | `[JOB]` |
| Partições | `[PARTIÇÃO]` |
| Particionador | `[PARTICIONAMENTO]` |
| Reader | `[LEITOR]` |
| Processor | `[PROCESSADOR]` |
| Writer | `[ESCRITOR]` |
| Elasticsearch | `[ELASTIC]` |
| Redis Coordinator | `[COORDENADOR]` |
| Redis Locks | `[LOCK]` |
| API REST | `[API]` |
| DLQ | `[DLQ]` |

---

## 🚀 Exemplo de Log Final (Português, Resumido)

```
2025-11-03 10:15:30 [JOB] Iniciado | id=123 | partições=100
2025-11-03 10:15:31 [PARTICIONAMENTO] Concluído | total=100 partições
2025-11-03 10:15:32 [PARTIÇÃO] Iniciada | id=0 | range=1-700000
2025-11-03 10:15:32 [PARTIÇÃO] Iniciada | id=1 | range=700001-1400000
...
2025-11-03 10:15:35 [ESCRITOR] Chunk enviado | tamanho=1000 | sucesso=998 | erros=2 | totalMs=275 | throughput=3636/s
2025-11-03 10:16:45 [PARTIÇÃO] Concluída | id=0 | status=COMPLETED | lidos=700000 | escritos=698500 | skips=1500
...
2025-11-03 12:30:15 [JOB] Concluído | status=COMPLETED | duração=135min | partições=100/100 | falhas=0 | throughput=8642/s
```

**Benefícios:**
- ✅ Fácil de ler (português)
- ✅ Estruturado (fácil parsear)
- ✅ Resumido (95% menos logs)
- ✅ Informativo (mantém dados importantes)

---

## 🔧 Configuração Logback (Recomendado)

```xml
<!-- logback-spring.xml -->
<configuration>
    <!-- Console: INFO para produção -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %msg%n</pattern>
        </encoder>
    </appender>

    <!-- Arquivo: DEBUG com rotação -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.log</file>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/application-%d{yyyy-MM-dd}.log.gz</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
    </appender>

    <!-- Níveis por pacote -->
    <logger name="prodesp.bio.migrador.batch.control.partition.RedisPartitionLock" level="WARN"/>
    <logger name="prodesp.bio.migrador.infra.client.ElasticClient" level="INFO"/>
    <logger name="org.springframework.batch" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

---

## ✅ Checklist de Implementação

- [ ] Traduzir todos os logs para português BR
- [ ] Consolidar logs verbosos (listeners, partitioner)
- [ ] Trocar INFO → DEBUG para locks
- [ ] Remover separadores visuais (`===`, `---`)
- [ ] Remover marcadores técnicos (`$$$$`)
- [ ] Usar tags de componente (`[JOB]`, `[PARTIÇÃO]`, etc.)
- [ ] Testar com 100 partições
- [ ] Validar parseabilidade (Logstash/Fluentd)
- [ ] Atualizar logback-spring.xml
- [ ] Atualizar README com exemplos de logs

---

## 📝 Próximos Passos

1. **Implementar mudanças** nos arquivos Java
2. **Testar localmente** com 10 partições
3. **Validar volume** de logs (deve reduzir 90%+)
4. **Atualizar README** com exemplos atualizados
5. **Documentar** padrão de logs no projeto

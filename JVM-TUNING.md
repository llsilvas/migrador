# JVM Tuning Guide - Migrador IIRGD

## Visão Geral

Este guia documenta as otimizações JVM para o Migrador IIRGD, focando em:
- **Virtual Threads (Java 21)**
- **Workload I/O-bound** (Elasticsearch Bulk API)
- **Alta concorrência** (100 partições simultâneas)

## Configurações Disponíveis

### Produção: `jvm-production.options`
- **Heap**: 16GB
- **GC**: ZGC Generational
- **Carrier Threads**: 32-64
- **Uso**: 70M registros, cluster produção

### Desenvolvimento: `jvm-development.options`
- **Heap**: 4GB
- **GC**: G1GC
- **Carrier Threads**: 16-32
- **Uso**: Testes locais, 100k registros

## Principais Otimizações

### 1. Garbage Collector

#### ZGC Generational (Produção)
```bash
-XX:+UseZGC
-XX:+ZGenerational
```

**Por quê ZGC?**
- ✓ Latência sub-milisegundo (pausas < 1ms)
- ✓ Escala para heaps grandes (16GB+)
- ✓ Pausas independentes do tamanho do heap
- ✓ Ideal para workloads com baixa latência

**Generational (Java 21+)**
- ✓ Melhor throughput que ZGC clássico
- ✓ Menor overhead de memória
- ✓ Coleta objetos jovens mais eficientemente

**Trade-offs:**
- ✗ Usa ~15% mais memória que G1GC
- ✗ Pode ter throughput menor em workloads CPU-bound

#### G1GC (Desenvolvimento)
```bash
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
```

**Por quê G1GC para dev?**
- ✓ Mais previsível e fácil de debugar
- ✓ Logs mais legíveis
- ✓ Menor consumo de memória
- ✓ Padrão Java 21

### 2. Virtual Threads Configuration

```bash
-Djdk.virtualThreadScheduler.parallelism=32
-Djdk.virtualThreadScheduler.maxPoolSize=64
```

**Carrier Threads (Platform Threads)**
- `parallelism`: Threads ativas simultaneamente
- `maxPoolSize`: Pool máximo durante picos

**Como escolher valores:**

| Ambiente | Cores CPU | parallelism | maxPoolSize |
|----------|-----------|-------------|-------------|
| Dev (Laptop) | 8-16 | 16 | 32 |
| Prod (Server) | 32+ | 32-64 | 64-128 |

**Regra de ouro para I/O-bound:**
```
parallelism = 2 × cores físicos
maxPoolSize = 2 × parallelism
```

**Por quê funciona?**
- Virtual threads liberam carrier threads durante bloqueios I/O
- Com 100 partições, teremos 100+ virtual threads
- Apenas ~32 carrier threads ficam ativos simultaneamente
- Resto fica "pinned" aguardando I/O (Elasticsearch)

### 3. Heap Sizing

```bash
-Xms16g
-Xmx16g
```

**Por quê Xms = Xmx?**
- ✓ Evita resizing durante execução
- ✓ Melhor previsibilidade de performance
- ✓ Menor fragmentação de memória

**Como calcular heap necessário:**

```
Heap = Base + (Chunk × Partitions × Overhead)

Onde:
- Base = Spring Boot + Batch metadata (~2GB)
- Chunk = 1000 registros × ~5KB/registro = 5MB
- Partitions = 100
- Overhead = 2x (garbage, buffers, etc.)

Heap = 2GB + (5MB × 100 × 2) = 2GB + 1GB = ~4GB mínimo

Para produção: 4GB × 4 = 16GB (margem de segurança)
```

### 4. Direct Memory (NIO)

```bash
-XX:MaxDirectMemorySize=2g
```

**O que é Direct Memory?**
- Memória fora do heap Java
- Usada por NIO buffers (RestClient HTTP)

**Por quê 2GB?**
```
Conexões HTTP = 200 (maxConnTotal)
Buffer por conexão = ~8MB
Total = 200 × 8MB = 1.6GB → arredondar para 2GB
```

### 5. Metaspace

```bash
-XX:MetaspaceSize=512m
-XX:MaxMetaspaceSize=1g
```

**O que é Metaspace?**
- Armazena classes carregadas (bytecode)
- Spring Boot + Batch carregam MUITAS classes

**Por quê 512MB-1GB?**
- Spring Boot típico: 200-400MB
- Batch listeners/proxies: +100MB
- Margem de segurança: +200MB

### 6. Performance Flags

#### AlwaysPreTouch
```bash
-XX:+AlwaysPreTouch
```
- ✓ Aloca heap inteiro no startup
- ✓ Evita page faults durante execução
- ✗ Startup ~10-20s mais lento

**Quando usar:** Produção (latência previsível > startup rápido)

#### String Deduplication
```bash
-XX:+UseStringDeduplication
```
- ✓ Economiza memória com strings duplicadas
- ✓ Útil para IDs, JSONs, metadados repetidos
- ✓ Overhead mínimo (~1-2%)

**Quando usar:** Sempre (sem desvantagens significativas)

#### Compressed OOPs
```bash
-XX:+UseCompressedOops
```
- ✓ Ponteiros 32-bit ao invés de 64-bit
- ✓ Economiza ~30% de memória
- ✗ Apenas para heaps < 32GB

**Quando usar:** Heaps até 32GB (nosso caso: 16GB)

## Comparação de Performance

### Cenário Base (Platform Threads)
```
Partições: 10
Threads: 10 platform threads
Throughput: ~1000 registros/s
Tempo (70M): ~19.4 horas
```

### Com Virtual Threads
```
Partições: 100
Threads: 100 virtual threads (32 carrier threads)
Throughput estimado: 5000-10000 registros/s
Tempo estimado (70M): 2-4 horas
```

### Com Virtual Threads + JVM Tuning
```
Partições: 100
Threads: 100 virtual threads (32 carrier threads)
GC: ZGC (pausas < 1ms vs G1GC ~50ms)
Throughput estimado: 8000-12000 registros/s
Tempo estimado (70M): 1.5-2.5 horas
```

**Ganho total estimado: 7-13x mais rápido**

## Como Usar

### 1. Build
```bash
mvn clean package
```

### 2. Executar - Desenvolvimento
```bash
./run-development.sh
```

### 3. Executar - Produção
```bash
./run-production.sh
```

### 4. Customizar (se necessário)
```bash
# Editar arquivo de opções
vim jvm-production.options

# Executar com opções customizadas
java @jvm-production.options -jar migrador-app/target/migrador-app-1.0.0.jar
```

## Monitoramento

### GC Logs
```bash
# Ver logs de GC
tail -f logs/gc.log

# Analisar GC (usar GCViewer ou similar)
https://github.com/chewiebug/GCViewer
```

### JMX (VisualVM, JConsole)
```bash
# Conectar em: localhost:9010
jconsole localhost:9010
```

### JDK Flight Recorder
```bash
# Analisar gravação
jmc logs/migrador-jfr.jfr
```

### Métricas no Log
Procure por:
```
--- Thread Info [BEFORE JOB] ---
Current Thread: ... (virtual=true)
Active Platform Threads: 32
Available Processors: 16

Throughput: 8500/s (70000000 total records in 8235s)
```

## Troubleshooting

### OOM (OutOfMemoryError)

#### Heap Space
```
Error: java.lang.OutOfMemoryError: Java heap space
```
**Solução:** Aumentar `-Xmx` ou reduzir `chunk-size`

#### Direct Memory
```
Error: java.lang.OutOfMemoryError: Direct buffer memory
```
**Solução:** Aumentar `-XX:MaxDirectMemorySize`

#### Metaspace
```
Error: java.lang.OutOfMemoryError: Metaspace
```
**Solução:** Aumentar `-XX:MaxMetaspaceSize`

### High GC Overhead
```
GC is taking > 50% of CPU time
```
**Possíveis causas:**
1. Heap muito pequeno → aumentar `-Xmx`
2. Chunk size muito grande → reduzir `chunk-size`
3. GC errado → trocar G1GC por ZGC

### Thread Starvation
```
Partitions not starting / low throughput
```
**Verificar:**
1. `parallelism` muito baixo → aumentar
2. Carrier threads bloqueados → verificar synchronized blocks
3. Elasticsearch lento → verificar `max-conn-total`

## Referências

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [JEP 439: Generational ZGC](https://openjdk.org/jeps/439)
- [ZGC Tuning Guide](https://wiki.openjdk.org/display/zgc/Main)
- [Spring Boot Virtual Threads](https://spring.io/blog/2022/10/11/embracing-virtual-threads)

## Changelog

### v1.0.0 - 2025-10-31
- ✓ Configuração inicial com ZGC + Virtual Threads
- ✓ Scripts de execução prod/dev
- ✓ Documentação completa

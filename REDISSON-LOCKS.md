# Redisson Distributed Locks - Guia Completo

## O que foi melhorado?

### Antes (RedisTemplate + Lua Scripts)
```java
// Implementação manual com scripts Lua
private static final String UNLOCK_SCRIPT =
    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
    "  return redis.call('del', KEYS[1]) " +
    "else " +
    "  return 0 " +
    "end";

Boolean acquired = redisTemplate.opsForValue()
    .setIfAbsent(lockKey, lockValue, LOCK_TIMEOUT);
```

**Problemas:**
- ❌ Locks não reentrant (mesma thread não pode adquirir 2x)
- ❌ Sem watchdog (lock expira durante processamento longo)
- ❌ Retry não implementado (TODO na linha 134)
- ❌ Cleanup manual com `KEYS *` (O(N) - bloqueia Redis)
- ❌ Mais código, mais bugs

### Depois (Redisson RLock)
```java
RLock lock = redissonClient.getLock("migration:lock:partition:0");
boolean acquired = lock.tryLock(10, -1, TimeUnit.SECONDS);
```

**Melhorias:**
- ✅ **Reentrant locks** - thread pode adquirir múltiplas vezes
- ✅ **Watchdog automático** - renova lock durante processamento
- ✅ **Retry automático** - tenta até waitTime expirar
- ✅ **Fair locks** - FIFO ordering
- ✅ **Sem cleanup** - TTL gerenciado automaticamente
- ✅ **Menos código** - ~80 linhas vs ~175 linhas

---

## Principais Features

### 1. Reentrant Locks

Mesma thread pode adquirir o mesmo lock múltiplas vezes:

```java
RLock lock = redissonClient.getLock("mylock");

lock.lock();
System.out.println("Hold count: " + lock.getHoldCount()); // 1

lock.lock();  // ✓ Funciona! (mesma thread)
System.out.println("Hold count: " + lock.getHoldCount()); // 2

lock.unlock();
lock.unlock(); // Precisa unlock 2x
```

**Útil quando:**
- Método A chama método B
- Ambos tentam adquirir mesmo lock
- Sem reentrant = deadlock

### 2. Watchdog Automático

Se processamento demorar mais que lease time, Redisson renova automaticamente:

```java
RLock lock = redissonClient.getLock("partition:0");

// leaseTime = -1 → usa watchdog
lock.tryLock(10, -1, TimeUnit.SECONDS);

// Processamento demora 5 minutos
processLargePartition(); // Watchdog renova lock a cada 30s

lock.unlock();
```

**Configuração do Watchdog:**
```java
// RedisConfiguration.java
config.setLockWatchdogTimeout(60000L); // Renova a cada 60s
```

**Importante:**
- Watchdog só funciona se `leaseTime = -1`
- Se especificar leaseTime, lock expira após esse tempo (sem renovação)

### 3. Retry Automático

```java
// Tenta adquirir por até 10 segundos
boolean acquired = lock.tryLock(10, -1, TimeUnit.SECONDS);

if (!acquired) {
    // Outra instância está usando por > 10s
}
```

**Como funciona:**
1. Tenta adquirir imediatamente
2. Se falhar, aguarda e tenta novamente
3. Repete até `waitTime` expirar
4. Retorna `false` se não conseguir

### 4. Fair Locks (FIFO)

Garante que threads adquirem lock na ordem que chegaram:

```java
// RedisPartitionLock.java (linha 68-69)
private RLock getLock(int partitionNumber) {
    String lockKey = LOCK_PREFIX + partitionNumber;

    // Para fair lock (FIFO):
    return redissonClient.getFairLock(lockKey);

    // Para lock normal (default):
    // return redissonClient.getLock(lockKey);
}
```

**Trade-off:**
- ✅ Fair locks previnem starvation
- ❌ Performance ~10% menor que locks normais

**Quando usar:**
- Workloads onde todas partições devem ter chance justa
- Evitar que algumas partições nunca executem

### 5. MultiLock / RedLock

Para alta disponibilidade com múltiplos nós Redis:

```java
// Configuração para Redis Cluster
RLock lock1 = redissonClient1.getLock("partition:0");
RLock lock2 = redissonClient2.getLock("partition:0");
RLock lock3 = redissonClient3.getLock("partition:0");

// MultiLock: adquire em TODOS os nós
RLock multiLock = redisson.getMultiLock(lock1, lock2, lock3);

multiLock.lock();
// Lock só é considerado adquirido se conseguir em TODOS os nós
multiLock.unlock();
```

**RedLock (maioria):**
```java
// Precisa adquirir em > 50% dos nós
RLock redLock = redisson.getRedLock(lock1, lock2, lock3);
redLock.lock(); // OK se conseguir em 2/3 nós
```

---

## Configurações Avançadas

### 1. Lock Watchdog Timeout

Intervalo de renovação automática:

```java
// RedisConfiguration.java:86
config.setLockWatchdogTimeout(60000L); // 60 segundos
```

**Recomendações:**
- **Batch curto** (< 1 min): 30s (default)
- **Batch médio** (1-5 min): 60s
- **Batch longo** (> 5 min): 120s

**Trade-off:**
- Menor = mais renovações (overhead)
- Maior = risco de lock órfão se processo morrer

### 2. Connection Pool

```java
config.useSingleServer()
    .setConnectionPoolSize(64)       // Conexões simultâneas
    .setConnectionMinimumIdleSize(24); // Mínimo idle
```

**Cálculo:**
```
Pool size = num_partitions × 1.5
Exemplo: 100 partições → 150 conexões
```

**Para Redis Cluster:**
```java
config.useClusterServers()
    .addNodeAddress("redis://127.0.0.1:7000", "redis://127.0.0.1:7001")
    .setMasterConnectionPoolSize(100)
    .setSlaveConnectionPoolSize(100);
```

### 3. Retry Strategy

```java
config.useSingleServer()
    .setRetryAttempts(3)      // Retry 3x
    .setRetryInterval(1500);   // 1.5s entre retries
```

### 4. Ping Interval

```java
config.useSingleServer()
    .setPingConnectionInterval(30000) // Ping a cada 30s
    .setKeepAlive(true);
```

**Por quê:**
- Mantém conexões vivas
- Detecta falhas de rede rapidamente

---

## Padrões de Uso

### Padrão 1: Try-Lock Simples

```java
RLock lock = redissonClient.getLock("partition:0");

if (lock.tryLock()) {
    try {
        // Processamento
    } finally {
        lock.unlock();
    }
} else {
    log.warn("Partition 0 is busy");
}
```

### Padrão 2: Try-Lock com Timeout

```java
RLock lock = redissonClient.getLock("partition:0");

if (lock.tryLock(10, -1, TimeUnit.SECONDS)) {
    try {
        // Processamento
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

### Padrão 3: Execute with Lock (Recomendado)

```java
// RedisPartitionLock.java:173-204
partitionLock.executeWithLock(partitionNumber, () -> {
    // Processamento
    return result;
}, 10, TimeUnit.SECONDS);
```

**Vantagens:**
- Try-finally automático
- Verificação de ownership
- Menos chance de esquecer unlock

### Padrão 4: Lock com Lease Time Fixo

```java
// Lock expira após 30s, SEM watchdog
lock.tryLock(10, 30, TimeUnit.SECONDS);
```

**Quando usar:**
- Processamento garantidamente < 30s
- Quer evitar overhead do watchdog
- Segurança contra deadlocks

---

## Monitoramento

### 1. Lock Info (Debug)

```java
public String getLockInfo(int partitionNumber) {
    RLock lock = getLock(partitionNumber);

    return String.format(
        "Lock[partition=%d, isLocked=%s, heldByCurrentThread=%s, " +
        "holdCount=%d, remainTimeToLive=%dms]",
        partitionNumber,
        lock.isLocked(),
        lock.isHeldByCurrentThread(),
        lock.getHoldCount(),
        lock.remainTimeToLive()
    );
}
```

### 2. Verificar Locks Ativos (Redis CLI)

```bash
# Ver todos os locks
redis-cli

> SCAN 0 MATCH migration:lock:partition:* COUNT 100
1) "0"
2) 1) "migration:lock:partition:0"
   2) "migration:lock:partition:5"
   3) "migration:lock:partition:12"

# Ver detalhes de um lock
> GET migration:lock:partition:0
"0ae3a4e2-5f1c-4f63-9c3e-f4d1b2c8a9d0:42" # UUID:threadId

> TTL migration:lock:partition:0
(integer) 58  # 58 segundos restantes
```

### 3. Métricas Prometheus

Redisson expõe métricas JMX que podem ser exportadas para Prometheus:

```yaml
management:
  metrics:
    export:
      prometheus:
        enabled: true
```

**Métricas disponíveis:**
- `redisson_locks_acquired_total` - Locks adquiridos
- `redisson_locks_wait_time_seconds` - Tempo esperando por lock
- `redisson_connections_active` - Conexões ativas

---

## Troubleshooting

### Lock Órfão (Stuck)

**Sintoma:**
```
Partition 5 is always locked, but no job is running
```

**Causa:**
- Processo morreu sem liberar lock
- Watchdog não conseguiu renovar (rede caiu)

**Solução 1: Force Unlock (Admin)**
```bash
redis-cli DEL migration:lock:partition:5
```

**Solução 2: Via API**
```java
partitionLock.forceUnlock(5);
```

### Lock Expira Durante Processamento

**Sintoma:**
```
IllegalMonitorStateException: attempt to unlock lock, not locked by current thread
```

**Causa:**
- Processamento demorou > watchdog timeout
- Lock expirou enquanto thread processava

**Solução:**
```java
// Aumentar watchdog timeout
config.setLockWatchdogTimeout(120000L); // 2 minutos
```

### Threads Competindo por Lock

**Sintoma:**
```
High CPU usage, threads spinning trying to acquire lock
```

**Causa:**
- Múltiplas threads tentando mesmo lock
- Retry sem backoff

**Solução: Usar Fair Lock**
```java
return redissonClient.getFairLock(lockKey);
```

---

## Comparação de Performance

| Feature | RedisTemplate | Redisson RLock |
|---------|---------------|----------------|
| **Código** | ~175 linhas | ~80 linhas |
| **Reentrant** | ❌ Não | ✅ Sim |
| **Watchdog** | ❌ Manual | ✅ Automático |
| **Retry** | ❌ Não implementado | ✅ Sim |
| **Fair Lock** | ❌ Não | ✅ Opcional |
| **Cleanup** | ⚠️ KEYS * (O(N)) | ✅ Automático (TTL) |
| **Overhead** | Baixo | Médio (+10%) |
| **Bugs** | Alto risco | Baixo (testado) |

**Veredito:** Redisson é superior em quase todos os aspectos, exceto overhead mínimo (~10%).

---

## Migração Checklist

- [x] Configurar RedissonClient
- [x] Refatorar RedisPartitionLock
- [x] Remover scripts Lua
- [x] Remover cleanupExpiredLocks
- [x] Atualizar testes
- [ ] Monitorar performance em staging
- [ ] Deploy em produção

---

## Referências

- [Redisson Documentation](https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers)
- [Redis Distributed Locks](https://redis.io/docs/manual/patterns/distributed-locks/)
- [RedLock Algorithm](https://redis.io/topics/distlock)

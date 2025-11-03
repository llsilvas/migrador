package prodesp.bio.migrador.batch.control.partition;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import prodesp.bio.migrador.core.port.PartitionLockPort;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Implementação de locks distribuídos usando Redisson RLock.
 *
 * MELHORIAS vs implementação manual com RedisTemplate:
 * =====================================================
 *
 * 1. REENTRANT LOCKS
 *    - Mesma thread pode adquirir o mesmo lock múltiplas vezes
 *    - Útil se código chamar métodos que também tentam lock
 *
 * 2. WATCHDOG AUTOMÁTICO
 *    - Se processamento levar > lease time, Redisson renova automaticamente
 *    - Evita lock expirar durante processamento longo
 *    - Default: 30s (configurável via lockWatchdogTimeout)
 *
 * 3. RETRY COM BACKOFF
 *    - tryLock(waitTime) já implementa retry automático
 *    - Não precisa de loop manual
 *
 * 4. FAIR LOCKS
 *    - Garante ordem FIFO (primeiro que chegar, primeiro que adquire)
 *    - Evita thread starvation
 *
 * 5. SEM SCRIPTS LUA
 *    - Redisson gerencia atomicidade internamente
 *    - Menos código, menos bugs
 *
 * 6. MULTI-NODE (RedLock)
 *    - Suporta locks em múltiplos nós Redis (alta disponibilidade)
 *    - Usa algoritmo RedLock do Redis
 */
@Component
@Slf4j
public class RedisPartitionLock implements PartitionLockPort {

    private static final String LOCK_PREFIX = "migration:lock:partition:";

    private final RedissonClient redissonClient;

    // Cache local de locks para reuso (Redisson gerencia internamente)
    private final Map<Integer, RLock> locks = new ConcurrentHashMap<>();

    public RedisPartitionLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        log.info("RedisPartitionLock initialized with Redisson RLock");
    }

    /**
     * Obtém ou cria RLock para uma partição.
     * Redisson caches locks internamente, mas fazemos cache adicional por segurança.
     */
    private RLock getLock(int partitionNumber) {
        return locks.computeIfAbsent(partitionNumber, partition -> {
            String lockKey = LOCK_PREFIX + partition;
            // Para fair lock (FIFO), usar: redissonClient.getFairLock(lockKey)
            return redissonClient.getLock(lockKey);
        });
    }

    /**
     * Tenta adquirir lock sem esperar.
     * Retorna imediatamente se lock já estiver ocupado.
     */
    public boolean acquireLock(int partitionNumber) {
        RLock lock = getLock(partitionNumber);

        try {
            // tryLock() sem argumentos = tenta 1x e retorna imediatamente
            boolean acquired = lock.tryLock();

            if (acquired) {
                log.info("✓ Acquired lock for partition {} (thread: {}, reentrant count: {})",
                        partitionNumber,
                        Thread.currentThread().getName(),
                        lock.getHoldCount());
                return true;
            } else {
                log.warn("✗ Partition {} is already locked by another instance", partitionNumber);
                return false;
            }
        } catch (Exception e) {
            log.error("Error acquiring lock for partition {}: {}", partitionNumber, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Tenta adquirir lock com retry durante waitTime.
     *
     * MELHORADO: Agora implementa retry real (não mais TODO!)
     * Redisson faz retry automático até waitTime expirar.
     */
    @Override
    public boolean tryLock(int partitionNumber, long waitTime, TimeUnit unit) {
        RLock lock = getLock(partitionNumber);

        try {
            // tryLock(waitTime, leaseTime, unit)
            // waitTime: quanto tempo esperar tentando adquirir
            // leaseTime: -1 = usa watchdog (renova automaticamente)
            boolean acquired = lock.tryLock(waitTime, -1, unit);

            if (acquired) {
                log.info("✓ Acquired lock for partition {} after waiting (thread: {}, holdCount: {})",
                        partitionNumber,
                        Thread.currentThread().getName(),
                        lock.getHoldCount());
                return true;
            } else {
                log.warn("✗ Failed to acquire lock for partition {} after {}{}",
                        partitionNumber, waitTime, unit);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for lock on partition {}", partitionNumber);
            return false;
        } catch (Exception e) {
            log.error("Error acquiring lock for partition {}: {}", partitionNumber, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Libera o lock.
     *
     * Redisson verifica automaticamente:
     * - Se esta thread é dona do lock
     * - Se lock ainda está válido
     * - Decrementa holdCount (reentrant)
     */
    @Override
    public void unlock(int partitionNumber) {
        RLock lock = getLock(partitionNumber);

        try {
            // Verificar se esta thread possui o lock antes de liberar
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("✓ Released lock for partition {} (remaining holdCount: {})",
                        partitionNumber,
                        lock.getHoldCount());
            } else {
                log.warn("Attempted to unlock partition {} but current thread doesn't hold the lock",
                        partitionNumber);
            }
        } catch (IllegalMonitorStateException e) {
            log.warn("Lock for partition {} was not held or already released", partitionNumber);
        } catch (Exception e) {
            log.error("Error releasing lock for partition {}: {}", partitionNumber, e.getMessage(), e);
        }
    }

    /**
     * Executa ação dentro de lock (try-finally automático).
     *
     * MELHOR PRÁTICA: Usar este método ao invés de tryLock + finally manual.
     */
    @Override
    public <T> T executeWithLock(int partitionNumber, Supplier<T> action, long waitTime, TimeUnit unit) {
        RLock lock = getLock(partitionNumber);

        try {
            // Tenta adquirir com timeout
            boolean acquired = lock.tryLock(waitTime, -1, unit);

            if (!acquired) {
                throw new IllegalStateException(
                        String.format("Failed to acquire lock for partition %d after %d%s",
                                partitionNumber, waitTime, unit)
                );
            }

            try {
                log.debug("Executing action with lock for partition {}", partitionNumber);
                return action.get();
            } finally {
                // Sempre libera lock, mesmo se ação falhar
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    log.debug("Lock released after action for partition {}", partitionNumber);
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for lock", e);
        } catch (Exception e) {
            throw new RuntimeException("Error executing with lock for partition " + partitionNumber, e);
        }
    }

    /**
     * Força unlock (admin/recovery).
     *
     * CUIDADO: Usar apenas para recovery de locks órfãos.
     * Pode causar race conditions se usado incorretamente.
     */
    @Override
    public void forceUnlock(int partitionNumber) {
        RLock lock = getLock(partitionNumber);

        try {
            // forceUnlock() não verifica ownership - remove independente de quem possui
            lock.forceUnlock();
            log.warn("⚠️ Force unlocked partition {} (admin operation)", partitionNumber);
        } catch (Exception e) {
            log.error("Error force unlocking partition {}: {}", partitionNumber, e.getMessage(), e);
        }
    }

    /**
     * Verifica se partição está locked (por qualquer instância).
     */
    public boolean isLocked(int partitionNumber) {
        RLock lock = getLock(partitionNumber);
        return lock.isLocked();
    }

    /**
     * Verifica se lock é possuído por esta thread.
     */
    public boolean isHeldByCurrentThread(int partitionNumber) {
        RLock lock = getLock(partitionNumber);
        return lock.isHeldByCurrentThread();
    }

    /**
     * Obtém informações sobre o lock (debug/monitoring).
     */
    public String getLockInfo(int partitionNumber) {
        RLock lock = getLock(partitionNumber);

        return String.format(
                "Lock[partition=%d, isLocked=%s, heldByCurrentThread=%s, holdCount=%d, remainTimeToLive=%dms]",
                partitionNumber,
                lock.isLocked(),
                lock.isHeldByCurrentThread(),
                lock.getHoldCount(),
                lock.remainTimeToLive()
        );
    }

    /**
     * REMOVIDO: cleanupExpiredLocks()
     *
     * NÃO É MAIS NECESSÁRIO porque:
     * 1. Redisson gerencia expiração automaticamente via TTL
     * 2. Watchdog renova locks ativos
     * 3. Locks expiram sozinhos se não forem renovados
     * 4. Evita uso de KEYS * pattern (O(N))
     *
     * Para monitorar locks órfãos, use Redis CLI manualmente:
     * SCAN 0 MATCH migration:lock:partition:* COUNT 100
     */
}

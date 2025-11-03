package prodesp.bio.migrador.infra.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import prodesp.bio.migrador.core.port.PartitionCoordinatorPort;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Implementação Redis do coordenador de partições.
 *
 * Armazena estado e progresso de cada partição no Redis para:
 * - Coordenação distribuída entre múltiplas instâncias
 * - Visibilidade do progresso em tempo real
 * - Recuperação em caso de falhas
 */
@Slf4j
@Service
public class RedisPartitionCoordinator implements PartitionCoordinatorPort {

    private static final String PARTITION_STATUS_PREFIX = "migration:partition:status:";
    private static final String PARTITION_PROGRESS_PREFIX = "migration:partition:progress:";
    private static final String PARTITION_METADATA_PREFIX = "migration:partition:metadata:";
    private static final String PARTITIONS_SET = "migration:partition:all";

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisPartitionCoordinator(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void registerPartition(int partitionNumber, long totalRecords) {
        String metadataKey = PARTITION_METADATA_PREFIX + partitionNumber;
        String statusKey = PARTITION_STATUS_PREFIX + partitionNumber;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("totalRecords", totalRecords);
        metadata.put("registeredAt", System.currentTimeMillis());

        redisTemplate.opsForHash().putAll(metadataKey, metadata);
        redisTemplate.opsForValue().set(statusKey, PartitionStatus.PENDING.name());
        redisTemplate.opsForSet().add(PARTITIONS_SET, String.valueOf(partitionNumber));

        log.debug("Registered partition {} with {} records", partitionNumber, totalRecords);
    }

    @Override
    public void startPartition(int partitionNumber) {
        String statusKey = PARTITION_STATUS_PREFIX + partitionNumber;
        String metadataKey = PARTITION_METADATA_PREFIX + partitionNumber;

        redisTemplate.opsForValue().set(statusKey, PartitionStatus.PROCESSING.name());
        redisTemplate.opsForHash().put(metadataKey, "startedAt", System.currentTimeMillis());

        log.info("Partition {} started processing", partitionNumber);
    }

    @Override
    public void completePartition(int partitionNumber) {
        String statusKey = PARTITION_STATUS_PREFIX + partitionNumber;
        String metadataKey = PARTITION_METADATA_PREFIX + partitionNumber;

        redisTemplate.opsForValue().set(statusKey, PartitionStatus.COMPLETED.name());
        redisTemplate.opsForHash().put(metadataKey, "completedAt", System.currentTimeMillis());

        log.info("Partition {} completed successfully", partitionNumber);
    }

    @Override
    public void failPartition(int partitionNumber) {
        String statusKey = PARTITION_STATUS_PREFIX + partitionNumber;
        String metadataKey = PARTITION_METADATA_PREFIX + partitionNumber;

        redisTemplate.opsForValue().set(statusKey, PartitionStatus.FAILED.name());
        redisTemplate.opsForHash().put(metadataKey, "failedAt", System.currentTimeMillis());

        log.error("Partition {} marked as FAILED", partitionNumber);
    }

    @Override
    public void updateProgress(int partitionNumber, long processed, long total) {
        String progressKey = PARTITION_PROGRESS_PREFIX + partitionNumber;

        Map<String, Object> progress = new HashMap<>();
        progress.put("processed", processed);
        progress.put("total", total);
        progress.put("percentage", total > 0 ? (processed * 100.0 / total) : 0);
        progress.put("updatedAt", System.currentTimeMillis());

        redisTemplate.opsForHash().putAll(progressKey, progress);

        if (log.isTraceEnabled()) {
            log.trace("Updated progress for partition {}: {}/{} ({:.2f}%)",
                    partitionNumber, processed, total,
                    total > 0 ? (processed * 100.0 / total) : 0);
        }
    }

    @Override
    public Map<Integer, PartitionStatus> getAllPartitionsStatus() {
        Map<Integer, PartitionStatus> statuses = new HashMap<>();

//        Set<String> keys = redisTemplate.keys(PARTITION_STATUS_PREFIX + "*");

        Set<Object> pasrtitionNumbers = redisTemplate.opsForSet().members(PARTITIONS_SET);
        if (pasrtitionNumbers != null) {
            for (Object partNum : pasrtitionNumbers) {
                try {
                    int partitionNumber = Integer.parseInt(
                            partNum.toString());
                    String statusKey = PARTITION_STATUS_PREFIX + partitionNumber;

                    Object statusObj = redisTemplate.opsForValue().get(statusKey);

                    if (statusObj != null) {

                        statuses.put(partitionNumber, PartitionStatus.valueOf(statusObj.toString()));
                    }
                } catch (Exception e) {
                    log.warn("Error parsing partition status from key {}: {}", pasrtitionNumbers, e.getMessage());
                }
            }
        }

        return statuses;
    }

    @Override
    public boolean areAllPartitionsComplete() {
        Map<Integer, PartitionStatus> statuses = getAllPartitionsStatus();

        if (statuses.isEmpty()) {
            return false;
        }

        return statuses.values().stream()
                .allMatch(status -> status == PartitionStatus.COMPLETED);
    }

    @Override
    public void clearPartitionMetadata() {
        log.info("Clearing all partition metadata...");

        Set<Object> partitions = redisTemplate.opsForSet().members(PARTITIONS_SET);
        // Limpar status
        if(partitions != null) {
            for (Object partNum : partitions) {
                int partitionNumber = Integer.parseInt(partNum.toString());

                redisTemplate.delete(PARTITION_STATUS_PREFIX + partitionNumber);
                redisTemplate.delete(PARTITION_PROGRESS_PREFIX + partitionNumber);
                redisTemplate.delete(PARTITION_METADATA_PREFIX + partitionNumber);
            }
        }

        redisTemplate.delete(PARTITIONS_SET);
    }
}
package prodesp.bio.migrador.infra.redis;


import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import prodesp.bio.migrador.core.domain.model.ColetaEntity;
import prodesp.bio.migrador.core.domain.model.FailedRecord;
import prodesp.bio.migrador.core.port.DeadLetterQueuePort;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RedisDeadLetterQueue implements DeadLetterQueuePort {

    private static final String DLQ_KEY = "migrador:dlq";
    private static final String DLQ_METADATA_KEY = "migrador:dlq:metadata";

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisDeadLetterQueue(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void addToDeadLetterQueue(ColetaEntity failedRecord, Exception error) {
        FailedRecord record = FailedRecord.builder()
                .idColetaValid(failedRecord.getIdColetaValid())
                .originalData(failedRecord)
                .errorMessage(error.getMessage())
                .errorClass(error.getClass().getName())
                .stackTrace(getStackTraceAsString(error))
                .timestamp(Instant.now())
                .retryCount(0)
                .partitionNumber(failedRecord.getPartitionNumber())
                .jobExecutionId(getJobExecutionId())
                .build();

        redisTemplate.opsForList().rightPush(DLQ_KEY, record);
        incrementDlqCounter(failedRecord.getPartitionNumber());

        log.error("Added record {} to DLQ from partition {}: {}",
                failedRecord.getIdColetaValid(),
                failedRecord.getPartitionNumber(),
                error.getMessage());
    }

    @Override
    public List<FailedRecord> getFailedRecords(int offset, int limit) {
        return redisTemplate.opsForList()
                .range(DLQ_KEY, offset, offset + limit - 1)
                .stream()
                .map(obj -> (FailedRecord) obj)
                .toList();
    }

    @Override
    public FailedRecord popFailedRecord() {
        Object result = redisTemplate.opsForList().leftPop(DLQ_KEY);
        return result != null ? (FailedRecord) result : null;
    }

    @Override
    public long getFailedRecordsCount() {
        Long size = redisTemplate.opsForList().size(DLQ_KEY);
        return size != null ? size : 0;
    }

    @Override
    public Map<String, Long> getDlqStatistics() {
        Map<Object, Object> entries =
                redisTemplate.opsForHash().entries(DLQ_METADATA_KEY);

        Map<String, Long> stats = new HashMap<>();
        entries.forEach((k, v) -> stats.put(k.toString(), Long.parseLong(v.toString())));

        stats.put("total", getFailedRecordsCount());
        return stats;
    }

    @Override
    public void clearDeadLetterQueue() {
        redisTemplate.delete(DLQ_KEY);
        redisTemplate.delete(DLQ_METADATA_KEY);
        log.warn("DLQ cleared");
    }

    private void incrementDlqCounter(Integer partitionNumber) {
        if (partitionNumber != null) {
            String key = "partition:" + partitionNumber;
            redisTemplate.opsForHash().increment(DLQ_METADATA_KEY, key, 1);
        }
    }

    private String getStackTraceAsString(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private Long getJobExecutionId() {
        try {
            StepExecution stepExecution = StepSynchronizationManager
                    .getContext().getStepExecution();
            return stepExecution != null ? stepExecution.getJobExecutionId() : null;
        } catch (Exception e) {
            return null;
        }
    }
}

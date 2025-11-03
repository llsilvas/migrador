package prodesp.bio.migrador.batch.control.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;
import prodesp.bio.migrador.core.port.DeadLetterQueuePort;
import prodesp.bio.migrador.core.port.PartitionCoordinatorPort;
import prodesp.bio.migrador.core.port.PartitionLockPort;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
public class MigrationJobExecutionListener implements JobExecutionListener {

    private final PartitionCoordinatorPort coordinator;
    private final PartitionLockPort lockManager;
    private final DeadLetterQueuePort dlq;

    public MigrationJobExecutionListener(
            PartitionCoordinatorPort coordinator,
            PartitionLockPort lockManager,
            DeadLetterQueuePort dlq) {
        this.coordinator = coordinator;
        this.lockManager = lockManager;
        this.dlq = dlq;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("[JOB] Iniciado | id={} | params={}",
                jobExecution.getJobId(),
                jobExecution.getJobParameters());

        // Log detalhado do ambiente (DEBUG)
        logThreadInfo();

        // Limpar metadados de execuções anteriores
        coordinator.clearPartitionMetadata();
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        LocalDateTime startTime = jobExecution.getStartTime();
        LocalDateTime endTime = jobExecution.getEndTime();
        Duration duration = startTime != null && endTime != null
                ? Duration.between(startTime, endTime)
                : Duration.ZERO;

        // Estatísticas de partições
        Map<Integer, PartitionCoordinatorPort.PartitionStatus> statuses =
                coordinator.getAllPartitionsStatus();

        long completed = statuses.values().stream()
                .filter(s -> s == PartitionCoordinatorPort.PartitionStatus.COMPLETED)
                .count();
        long failed = statuses.values().stream()
                .filter(s -> s == PartitionCoordinatorPort.PartitionStatus.FAILED)
                .count();

        // Calcular throughput e registros totais
        long totalRecords = 0;
        double recordsPerSecond = 0;
        if (duration.toSeconds() > 0) {
            totalRecords = jobExecution.getStepExecutions().stream()
                    .mapToLong(se -> se.getReadCount())
                    .sum();
            recordsPerSecond = (double) totalRecords / duration.toSeconds();
        }

        // Estatísticas DLQ
        Map<String, Long> dlqStats = dlq.getDlqStatistics();
        long dlqCount = dlqStats.getOrDefault("total", 0L);

        // ✅ LOG CONSOLIDADO - Todas métricas em 1 linha
        log.info("[JOB] Concluído | status={} | duração={}min | partições={}/{} | " +
                 "falhas={} | registros={} | throughput={}/s | dlq={}",
                jobExecution.getStatus(),
                duration.toMinutes(),
                completed,
                statuses.size(),
                failed,
                totalRecords,
                String.format("%.0f", recordsPerSecond),
                dlqCount);

        // Liberar todos os locks (cleanup)
        log.debug("[JOB] Liberando {} locks de partições", statuses.size());
        for (int i = 0; i < statuses.size(); i++) {
            try {
                lockManager.forceUnlock(i);
            } catch (Exception e) {
                log.warn("[JOB] Erro ao liberar lock | partição={} | erro={}", i, e.getMessage());
            }
        }
    }

    /**
     * Loga informações sobre threads (virtual vs platform) e memória.
     * Útil para troubleshooting e análise de performance.
     */
    private void logThreadInfo() {
        Thread currentThread = Thread.currentThread();
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        while (rootGroup.getParent() != null) {
            rootGroup = rootGroup.getParent();
        }

        int activeThreads = rootGroup.activeCount();
        Runtime runtime = Runtime.getRuntime();
        long usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);

        // ✅ LOG CONSOLIDADO DEBUG
        log.debug("[JOB] Ambiente | thread={} | virtual={} | plataformaThreads={} | cpus={} | memMB={}/{}",
                currentThread.getName(),
                currentThread.isVirtual(),
                activeThreads,
                Runtime.getRuntime().availableProcessors(),
                usedMemoryMB,
                maxMemoryMB);
    }
}

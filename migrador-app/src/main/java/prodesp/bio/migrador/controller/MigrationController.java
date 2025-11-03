package prodesp.bio.migrador.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import prodesp.bio.migrador.core.port.DeadLetterQueuePort;
import prodesp.bio.migrador.core.port.PartitionCoordinatorPort;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller REST para gerenciar a execução do job de migração.
 *
 * Endpoints disponíveis:
 * - POST /api/migration/start - Inicia uma nova execução do job
 * - GET /api/migration/status/{jobExecutionId} - Consulta status de uma execução
 * - GET /api/migration/partitions - Status de todas as partições
 * - GET /api/migration/dlq/count - Contador de registros na DLQ
 * - GET /api/migration/dlq/records - Registros falhados na DLQ
 */
@Slf4j
@RestController
@RequestMapping("/api/migration")
public class MigrationController {

    private final JobLauncher jobLauncher;
    private final Job migrationJob;
    private final JobExplorer jobExplorer;
    private final PartitionCoordinatorPort coordinator;
    private final DeadLetterQueuePort dlq;

    public MigrationController(
            JobLauncher jobLauncher,
            Job migrationJob,
            JobExplorer jobExplorer,
            PartitionCoordinatorPort coordinator,
            DeadLetterQueuePort dlq) {
        this.jobLauncher = jobLauncher;
        this.migrationJob = migrationJob;
        this.jobExplorer = jobExplorer;
        this.coordinator = coordinator;
        this.dlq = dlq;
    }

    /**
     * Inicia uma nova execução do job de migração.
     *
     * Exemplo de uso:
     * POST http://localhost:8080/migrador-iirgd/api/migration/start
     *
     * @return Informações sobre a execução iniciada
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startMigration() {
        try {
            log.info("=== Starting Migration Job ===");

            // Criar JobParameters únicos (necessário para cada execução)
            JobParameters jobParameters = new JobParametersBuilder()
                    .addString("startTime", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME))
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();

            // Executar o job de forma assíncrona
            JobExecution jobExecution = jobLauncher.run(migrationJob, jobParameters);

            log.info("Job started with executionId: {}", jobExecution.getId());

            // Preparar resposta
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Migration job started successfully");
            response.put("jobExecutionId", jobExecution.getId());
            response.put("jobInstanceId", jobExecution.getJobInstance().getId());
            response.put("status", jobExecution.getStatus().name());
            response.put("startTime", jobExecution.getStartTime());
            response.put("checkStatusUrl", "/api/migration/status/" + jobExecution.getId());

            return ResponseEntity.ok(response);

        } catch (JobExecutionAlreadyRunningException e) {
            log.error("Job is already running", e);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "error", "Job is already running",
                            "message", e.getMessage()
                    ));

        } catch (JobRestartException e) {
            log.error("Error restarting job", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "error", "Job restart failed",
                            "message", e.getMessage()
                    ));

        } catch (JobInstanceAlreadyCompleteException e) {
            log.error("Job instance already complete", e);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "error", "Job instance already completed",
                            "message", e.getMessage()
                    ));

        } catch (Exception e) {
            log.error("Unexpected error starting migration job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to start migration job",
                            "message", e.getMessage()
                    ));
        }
    }

    /**
     * Consulta o status de uma execução específica do job.
     *
     * Exemplo:
     * GET http://localhost:8080/migrador-iirgd/api/migration/status/1
     */
    @GetMapping("/status/{jobExecutionId}")
    public ResponseEntity<Map<String, Object>> getJobStatus(@PathVariable Long jobExecutionId) {
        try {
            JobExecution jobExecution = jobExplorer.getJobExecution(jobExecutionId);

            if (jobExecution == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "error", "Job execution not found",
                                "jobExecutionId", jobExecutionId
                        ));
            }

            Map<String, Object> response = new HashMap<>();

            // Basic job information
            response.put("jobExecutionId", jobExecution.getId());
            response.put("jobName", jobExecution.getJobInstance().getJobName());
            response.put("jobInstanceId", jobExecution.getJobInstance().getId());
            response.put("status", jobExecution.getStatus().name());
            response.put("exitCode", jobExecution.getExitStatus().getExitCode());
            response.put("exitDescription", jobExecution.getExitStatus().getExitDescription());

            // Timing information
            response.put("startTime", jobExecution.getStartTime());
            response.put("endTime", jobExecution.getEndTime());
            response.put("createTime", jobExecution.getCreateTime());
            response.put("lastUpdated", jobExecution.getLastUpdated());

            // Calculate duration
            if (jobExecution.getStartTime() != null) {
                LocalDateTime endTime = jobExecution.getEndTime() != null
                        ? jobExecution.getEndTime()
                        : LocalDateTime.now();

                LocalDateTime startTime = jobExecution.getStartTime();

                Duration duration = Duration.between(startTime, endTime);
                response.put("durationSeconds", duration.getSeconds());
                response.put("durationFormatted", formatDuration(duration));
            }

            // Step execution details
            Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();
            long totalRead = stepExecutions.stream().mapToLong(StepExecution::getReadCount).sum();
            long totalWrite = stepExecutions.stream().mapToLong(StepExecution::getWriteCount).sum();
            long totalSkip = stepExecutions.stream().mapToLong(StepExecution::getSkipCount).sum();
            long totalCommit = stepExecutions.stream().mapToLong(StepExecution::getCommitCount).sum();
            long totalRollback = stepExecutions.stream().mapToLong(StepExecution::getRollbackCount).sum();

            response.put("recordsRead", totalRead);
            response.put("recordsWritten", totalWrite);
            response.put("recordsSkipped", totalSkip);
            response.put("commitsCount", totalCommit);
            response.put("rollbacksCount", totalRollback);

            // Calculate throughput
            if (jobExecution.getStartTime() != null) {
                LocalDateTime endTime = jobExecution.getEndTime() != null
                        ? jobExecution.getEndTime()
                        : LocalDateTime.now();

                LocalDateTime startTime = jobExecution.getStartTime();

                long seconds = Duration.between(startTime, endTime).getSeconds();
                if (seconds > 0) {
                    long throughput = totalRead / seconds;
                    response.put("throughputPerSecond", throughput);

                    // Calculate ETA for completion if still running
                    if (jobExecution.getStatus() == BatchStatus.STARTED) {
                        // Estimate based on partition status
                        Map<Integer, PartitionCoordinatorPort.PartitionStatus> partitionStatuses =
                                coordinator.getAllPartitionsStatus();

                        long completedPartitions = partitionStatuses.values().stream()
                                .filter(s -> s == PartitionCoordinatorPort.PartitionStatus.COMPLETED)
                                .count();

                        long totalPartitions = partitionStatuses.size();

                        if (totalPartitions > 0 && completedPartitions > 0) {
                            double progress = (double) completedPartitions / totalPartitions;
                            response.put("progressPercentage", String.format("%.2f%%", progress * 100));

                            long elapsedSeconds = seconds;
                            long estimatedTotalSeconds = (long) (elapsedSeconds / progress);
                            long remainingSeconds = estimatedTotalSeconds - elapsedSeconds;

                            response.put("estimatedRemainingSeconds", remainingSeconds);
                            response.put("estimatedRemainingFormatted",
                                    formatDuration(Duration.ofSeconds(remainingSeconds)));
                        }
                    }
                }
            }

            // Step details (only include master step, not all partition steps)
            List<Map<String, Object>> stepDetails = stepExecutions.stream()
                    .filter(step -> !step.getStepName().contains("partition-"))
                    .map(step -> {
                        Map<String, Object> stepInfo = new HashMap<>();
                        stepInfo.put("stepName", step.getStepName());
                        stepInfo.put("status", step.getStatus().name());
                        stepInfo.put("readCount", step.getReadCount());
                        stepInfo.put("writeCount", step.getWriteCount());
                        stepInfo.put("skipCount", step.getSkipCount());
                        stepInfo.put("startTime", step.getStartTime());
                        stepInfo.put("endTime", step.getEndTime());
                        return stepInfo;
                    })
                    .collect(Collectors.toList());
            response.put("steps", stepDetails);

            // Partition summary
            Map<Integer, PartitionCoordinatorPort.PartitionStatus> partitionStatuses =
                    coordinator.getAllPartitionsStatus();

            long pending = partitionStatuses.values().stream()
                    .filter(s -> s == PartitionCoordinatorPort.PartitionStatus.PENDING).count();
            long processing = partitionStatuses.values().stream()
                    .filter(s -> s == PartitionCoordinatorPort.PartitionStatus.PROCESSING).count();
            long completed = partitionStatuses.values().stream()
                    .filter(s -> s == PartitionCoordinatorPort.PartitionStatus.COMPLETED).count();
            long failed = partitionStatuses.values().stream()
                    .filter(s -> s == PartitionCoordinatorPort.PartitionStatus.FAILED).count();

            Map<String, Object> partitionSummary = new HashMap<>();
            partitionSummary.put("total", partitionStatuses.size());
            partitionSummary.put("pending", pending);
            partitionSummary.put("processing", processing);
            partitionSummary.put("completed", completed);
            partitionSummary.put("failed", failed);
            response.put("partitionSummary", partitionSummary);

            // Failure information if job failed
            if (jobExecution.getStatus() == BatchStatus.FAILED) {
                List<Throwable> failures = jobExecution.getAllFailureExceptions();
                if (!failures.isEmpty()) {
                    List<String> errorMessages = failures.stream()
                            .map(Throwable::getMessage)
                            .collect(Collectors.toList());
                    response.put("failureReasons", errorMessages);
                }
            }

            // DLQ information
            long dlqCount = dlq.getFailedRecordsCount();
            response.put("failedRecordsInDLQ", dlqCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching job status for executionId: {}", jobExecutionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to fetch job status",
                            "message", e.getMessage(),
                            "jobExecutionId", jobExecutionId
                    ));
        }
    }

    /**
     * Formata uma duração em formato legível (HH:mm:ss).
     */
    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * Retorna o status de todas as partições.
     *
     * Exemplo:
     * GET http://localhost:8080/migrador-iirgd/api/migration/partitions
     */
    @GetMapping("/partitions")
    public ResponseEntity<Map<String, Object>> getPartitionsStatus() {
        Map<Integer, PartitionCoordinatorPort.PartitionStatus> statuses =
                coordinator.getAllPartitionsStatus();

        long pending = statuses.values().stream()
                .filter(s -> s == PartitionCoordinatorPort.PartitionStatus.PENDING)
                .count();
        long processing = statuses.values().stream()
                .filter(s -> s == PartitionCoordinatorPort.PartitionStatus.PROCESSING)
                .count();
        long completed = statuses.values().stream()
                .filter(s -> s == PartitionCoordinatorPort.PartitionStatus.COMPLETED)
                .count();
        long failed = statuses.values().stream()
                .filter(s -> s == PartitionCoordinatorPort.PartitionStatus.FAILED)
                .count();

        Map<String, Object> response = new HashMap<>();
        response.put("total", statuses.size());
        response.put("pending", pending);
        response.put("processing", processing);
        response.put("completed", completed);
        response.put("failed", failed);
        response.put("allComplete", coordinator.areAllPartitionsComplete());
        response.put("partitions", statuses);

        return ResponseEntity.ok(response);
    }

    /**
     * Retorna a contagem de registros na Dead Letter Queue (DLQ).
     *
     * Exemplo:
     * GET http://localhost:8080/migrador-iirgd/api/migration/dlq/count
     */
    @GetMapping("/dlq/count")
    public ResponseEntity<Map<String, Object>> getDlqCount() {
        long count = dlq.getFailedRecordsCount();
        Map<String, Long> stats = dlq.getDlqStatistics();

        Map<String, Object> response = new HashMap<>();
        response.put("totalFailedRecords", count);
        response.put("statistics", stats);

        return ResponseEntity.ok(response);
    }

    /**
     * Retorna registros falhados da DLQ.
     *
     * Exemplo:
     * GET http://localhost:8080/migrador-iirgd/api/migration/dlq/records?limit=10
     */
    @GetMapping("/dlq/records")
    public ResponseEntity<Map<String, Object>> getDlqRecords(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {

        var failedRecords = dlq.getFailedRecords(offset, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("offset", offset);
        response.put("limit", limit);
        response.put("count", failedRecords.size());
        response.put("records", failedRecords);

        return ResponseEntity.ok(response);
    }

    /**
     * Limpa todos os metadados de partições (útil para testes).
     *
     * Exemplo:
     * POST http://localhost:8080/migrador-iirgd/api/migration/cleanup
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanup() {
        log.warn("Cleaning up partition metadata");
        coordinator.clearPartitionMetadata();

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Partition metadata cleared successfully");

        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint.
     *
     * Exemplo:
     * GET http://localhost:8080/migrador-iirgd/api/migration/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "Migration Controller",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
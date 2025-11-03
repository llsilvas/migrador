// src/main/java/prodesp/bio/migrador/batch/control/config/BatchJobConfig.java
package prodesp.bio.migrador.batch.control.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import prodesp.bio.migrador.batch.control.listener.MigrationJobExecutionListener;
import prodesp.bio.migrador.batch.control.partition.RangePartitioner;
import prodesp.bio.migrador.core.port.PartitionCoordinatorPort;

import java.util.concurrent.Executors;

@Slf4j
@Configuration
public class BatchJobConfig {

    @Value("${migration.partitions:100}")
    private int partitions;

    @Value("${migration.min-id:1}")
    private long minId;

    @Value("${migration.max-id:70000000}")
    private long maxId;

    @Value("${migration.thread-pool.core-size:10}")
    private int corePoolSize;

    @Value("${migration.thread-pool.max-size:20}")
    private int maxPoolSize;

    @Value("${migration.thread-pool.queue-capacity:50}")
    private int queueCapacity;

    @Bean
    public Job migrationJob(
            JobRepository jobRepository,
            Step masterStep,
            MigrationJobExecutionListener jobExecutionListener) {
        return new JobBuilder("migration-iirgd-job", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(masterStep)
                .listener(jobExecutionListener)
                .build();
    }

    @Bean
    public Step masterStep(
            JobRepository jobRepository,
            Partitioner partitioner,
            Step workerStep,
            @Qualifier("migrationTaskExecutor") TaskExecutor taskExecutor) {
        return new StepBuilder("masterStep", jobRepository)
                .partitioner("workerStep", partitioner)
                .step(workerStep)
                .gridSize(partitions)
                .taskExecutor(taskExecutor)
                .build();
    }

    @Bean
    public Partitioner rangePartitioner(PartitionCoordinatorPort coordinator) {
        log.info("Creating RangePartitioner: minId={}, maxId={}, partitions={}",
                minId, maxId, partitions);
        return new RangePartitioner(minId, maxId, partitions, coordinator);
    }

    @Bean("migrationTaskExecutor")
    public TaskExecutor migrationTaskExecutor() {
        // Virtual Threads para operações I/O-bound (Elasticsearch Bulk API)
        // Vantagens:
        // - Sem limite de platform threads (escala para 100s de partições)
        // - Carrier threads liberadas durante bloqueios de I/O
        // - Menor latência geral com mais paralelização
        TaskExecutorAdapter executor = new TaskExecutorAdapter(
            Executors.newVirtualThreadPerTaskExecutor()
        );

        log.info("=== TaskExecutor configured with VIRTUAL THREADS ===");
        log.info("Virtual threads enabled: unlimited concurrency for I/O-bound operations");
        log.info("Expected improvement: 5-10x throughput vs platform threads (core={}, max={})",
                corePoolSize, maxPoolSize);

        return executor;
    }

    /**
     * Platform thread executor (fallback/legacy - não usado por padrão).
     * Manter para comparação de performance se necessário.
     */
    @Bean("platformThreadExecutor")
    public TaskExecutor platformThreadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("migration-partition-platform-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        log.info("Platform TaskExecutor configured: core={}, max={}, queue={}",
                corePoolSize, maxPoolSize, queueCapacity);

        return executor;
    }
}
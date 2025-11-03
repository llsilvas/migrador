package prodesp.bio.migrador.batch.workers.config;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.QueryTimeoutException;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import prodesp.bio.migrador.core.domain.model.ColetaEntity;
import prodesp.bio.migrador.core.domain.model.ColetaMetadata;

import java.io.IOException;
import java.net.SocketTimeoutException;

@Slf4j
@Configuration
public class WorkerStepConfig {

    @Value("${migration.chunk-size:1000}")
    private int chunkSize;

    @Value("${migration.skip-limit:50}")
    private int skipLimit;

    @Value("${migration.retry-limit:3}")
    private int retryLimit;

    @Value("${migration.throttle-limit:8}")
    private int throttleLimit;

    @Bean
    public Step workerStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemReader<ColetaEntity> reader,
            ItemProcessor<ColetaEntity, ColetaMetadata> processor,
            ItemWriter<ColetaMetadata> writer,
            ChunkListener chunkListener) {

        return new StepBuilder("workerStep", jobRepository)
                .<ColetaEntity, ColetaMetadata>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()

                // Skip configuration - apenas erros específicos e recuperáveis
                .skipLimit(skipLimit)
                .skip(TransientDataAccessException.class)
                .skip(ElasticsearchException.class)
                .skip(SocketTimeoutException.class)
                .skip(JsonProcessingException.class)

                // Não skip em erros graves
                .noSkip(NullPointerException.class)
                .noSkip(OutOfMemoryError.class)
                .noSkip(StackOverflowError.class)

                // Retry configuration - apenas para erros temporários
                .retryLimit(retryLimit)
                .retry(DeadlockLoserDataAccessException.class)
                .retry(QueryTimeoutException.class)
                .retry(SocketTimeoutException.class)
                .retry(ElasticsearchException.class)

                // Listeners e throttle
                .listener(chunkListener)
                .throttleLimit(throttleLimit)

                .build();
    }

    /**
     * Skip policy customizada para logging detalhado.
     * Pode ser ativada via configuração se necessário.
     */
    @Bean
    public SkipPolicy customSkipPolicy() {
        return (throwable, skipCount) -> {
            if (skipCount >= skipLimit) {
                log.error("Skip limit exceeded: {} skips with last error: {}",
                        skipCount, throwable.getMessage());
                return false;
            }

            // Log cada skip para auditoria
            log.warn("Skipping item due to {}: {} (skip count: {})",
                    throwable.getClass().getSimpleName(),
                    throwable.getMessage(),
                    skipCount + 1);

            // Permitir skip apenas de erros recuperáveis
            return throwable instanceof DataAccessException ||
                   throwable instanceof ElasticsearchException ||
                   throwable instanceof IOException;
        };
    }
}

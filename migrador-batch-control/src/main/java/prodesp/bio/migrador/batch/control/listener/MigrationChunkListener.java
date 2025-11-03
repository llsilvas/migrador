package prodesp.bio.migrador.batch.control.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.stereotype.Component;

/**
 * Listener que monitora a execução de cada chunk dentro de um step.
 *
 * Útil para:
 * - Monitorar progresso granular
 * - Debug de performance por chunk
 * - Detectar chunks problemáticos
 */
@Slf4j
@Component
public class MigrationChunkListener implements ChunkListener {

    private ThreadLocal<Long> chunkStartTime = ThreadLocal.withInitial(System::currentTimeMillis);

    @Override
    public void beforeChunk(ChunkContext context) {
        chunkStartTime.set(System.currentTimeMillis());

        if (log.isTraceEnabled()) {
            Integer partition = (Integer) context.getStepContext()
                    .getStepExecution()
                    .getExecutionContext()
                    .get("partition");

            log.trace("Starting chunk processing for partition {}", partition);
        }
    }

    @Override
    public void afterChunk(ChunkContext context) {
        long duration = System.currentTimeMillis() - chunkStartTime.get();

        Integer partition = (Integer) context.getStepContext()
                .getStepExecution()
                .getExecutionContext()
                .get("partition");

        long readCount = context.getStepContext().getStepExecution().getReadCount();

        if (log.isDebugEnabled()) {
            log.debug("Chunk completed for partition {} - Duration: {}ms, Total reads: {}",
                    partition, duration, readCount);
        }

        // Warning se chunk demorou muito (mais de 30 segundos)
        if (duration > 30000) {
            log.warn("Slow chunk detected for partition {} - took {}ms", partition, duration);
        }
    }

    @Override
    public void afterChunkError(ChunkContext context) {
        Integer partition = (Integer) context.getStepContext()
                .getStepExecution()
                .getExecutionContext()
                .get("partition");

        log.error("Chunk error occurred for partition {}", partition);

        // Limpar ThreadLocal para evitar memory leak
        chunkStartTime.remove();
    }
}
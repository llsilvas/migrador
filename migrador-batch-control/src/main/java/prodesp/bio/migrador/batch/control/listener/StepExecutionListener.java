package prodesp.bio.migrador.batch.control.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.AfterStep;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.stereotype.Component;
import prodesp.bio.migrador.core.port.PartitionCoordinatorPort;

/**
 * Listener que monitora a execução de cada step (partição) do batch.
 *
 * Responsável por:
 * - Registrar início/fim de cada partição
 * - Atualizar status no coordinator
 * - Logar estatísticas de processamento
 */
@Slf4j
@Component
public class StepExecutionListener implements org.springframework.batch.core.StepExecutionListener {

    private final PartitionCoordinatorPort coordinator;

    public StepExecutionListener(PartitionCoordinatorPort coordinator) {
        this.coordinator = coordinator;
    }

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        Integer partition = stepExecution.getExecutionContext().getInt("partition", -1);

        if (partition >= 0) {
            Long minId = stepExecution.getExecutionContext().getLong("minId", 0L);
            Long maxId = stepExecution.getExecutionContext().getLong("maxId", 0L);

            // ✅ LOG CONSOLIDADO - 1 linha
            log.info("[PARTIÇÃO] Iniciada | id={} | range={}-{}",
                    partition, minId, maxId);

            // Notificar coordinator que a partição iniciou
            coordinator.startPartition(partition);
        }
    }

    @AfterStep
    public ExitStatus afterStep(StepExecution stepExecution) {
        Integer partition = stepExecution.getExecutionContext().getInt("partition", -1);

        if (partition >= 0) {
            BatchStatus status = stepExecution.getStatus();
            long readCount = stepExecution.getReadCount();
            long writeCount = stepExecution.getWriteCount();
            long skipCount = stepExecution.getSkipCount();
            long commitCount = stepExecution.getCommitCount();
            long rollbackCount = stepExecution.getRollbackCount();

            // ✅ LOG CONSOLIDADO - Todas métricas em 1 linha
            log.info("[PARTIÇÃO] Concluída | id={} | status={} | lidos={} | escritos={} | " +
                     "skips={} | commits={} | rollbacks={}",
                    partition, status, readCount, writeCount,
                    skipCount, commitCount, rollbackCount);

            // Atualizar status no coordinator
            if (status == BatchStatus.COMPLETED) {
                coordinator.completePartition(partition);
            } else if (status == BatchStatus.FAILED) {
                coordinator.failPartition(partition);
                log.error("[PARTIÇÃO] FALHOU | id={} | verificar logs para detalhes", partition);
            }

            // Logar warnings se houver problemas significativos
            if (skipCount > 0 && readCount > 0) {
                double skipPercentage = (skipCount * 100.0) / readCount;
                if (skipPercentage > 5.0) { // Só avisar se > 5%
                    log.warn("[PARTIÇÃO] Skips elevados | id={} | quantidade={} | percentual={:.2f}%",
                            partition, skipCount, skipPercentage);
                }
            }

            if (rollbackCount > 0) {
                log.warn("[PARTIÇÃO] Rollbacks detectados | id={} | quantidade={}",
                        partition, rollbackCount);
            }
        }

        return stepExecution.getExitStatus();
    }
}
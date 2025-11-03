package prodesp.bio.migrador.batch.control.partition;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import prodesp.bio.migrador.core.port.PartitionCoordinatorPort;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@AllArgsConstructor
public class RangePartitioner implements Partitioner {

    private final long minId;
    private final long maxId;
    private final int gridSize;
    private final PartitionCoordinatorPort coordinator;


    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> partitions = new HashMap<>();

        long totalRecords = maxId - minId + 1;
        long recordsPerPartition = totalRecords / gridSize;
        long remainder = totalRecords % gridSize;

        log.info("[PARTICIONAMENTO] Criando {} partições para {} registros (~{} registros/partição)",
                gridSize, totalRecords, recordsPerPartition);

        long currentMin = minId;

        for (int i = 0; i < gridSize; i++) {
            // Distribui o resto nas primeiras partições
            long partitionSize = recordsPerPartition + (i < remainder ? 1 : 0);
            long currentMax = Math.min(currentMin + partitionSize - 1, maxId);

            ExecutionContext context = new ExecutionContext();
            context.putInt("partition", i);
            context.putLong("minId", currentMin);
            context.putLong("maxId", currentMax);
            context.putLong("estimatedRecords", partitionSize);

            partitions.put("partition" + i, context);

            // Registrar partição no Redis
            if (coordinator != null) {
                coordinator.registerPartition(i, partitionSize);
            }

            log.debug("[PARTICIONAMENTO] Partição {} | range={}-{} | estimativa={}",
                    i, currentMin, currentMax, partitionSize);

            currentMin = currentMax + 1;

            // Se já chegou ao final, parar
            if (currentMin > maxId) {
                break;
            }
        }

        log.info("[PARTICIONAMENTO] Concluído | total={} partições", partitions.size());
        return partitions;
    }
}

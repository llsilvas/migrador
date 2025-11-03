package prodesp.bio.migrador.batch.workers.reader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.SqlServerPagingQueryProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import prodesp.bio.migrador.core.domain.model.ColetaEntity;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Reader para ColetaEntity com suporte a particionamento por range de IDs.
 *
 * IMPLEMENTAÇÃO REAL usando JdbcPagingItemReader para ler do SQL Server.
 *
 * Features:
 * - Paginação eficiente (ROW_NUMBER no SQL Server)
 * - Suporte a particionamento (minId/maxId por partition)
 * - Thread-safe (cada partition tem sua própria instância)
 * - Cursor-based reading (não carrega tudo em memória)
 */
@Slf4j
@Configuration
public class ColetaItemReader {

    /**
     * Bean do JdbcPagingItemReader configurado para cada partição.
     *
     * @StepScope garante que cada partition recebe uma nova instância
     * com seus próprios parâmetros (minId, maxId, partition).
     */
    @Bean
    @StepScope
    public JdbcPagingItemReader<ColetaEntity> coletaReader(
            @Qualifier("iirgdDataSource") DataSource iirgdDataSource,
            @Value("#{stepExecutionContext['minId']}") Long minId,
            @Value("#{stepExecutionContext['maxId']}") Long maxId,
            @Value("#{stepExecutionContext['partition']}") Integer partitionNumber,
            @Value("${migration.chunk-size:1000}") int pageSize) {

        log.info("Creating JdbcPagingItemReader for partition {}: ID range [{} - {}]",
                partitionNumber, minId, maxId);

        // SQL Server Paging Query Provider
        SqlServerPagingQueryProvider queryProvider = new SqlServerPagingQueryProvider();

        // SELECT clause
        queryProvider.setSelectClause(
                "ID, ID_COLETA_VALID, CPF, RG, DATA_COLETA, TIPO_COLETA, IMAGEM_DIGITAL"
        );

        // FROM clause
        queryProvider.setFromClause("TB_COLETA");

        // WHERE clause - filtro por range de ID (particionamento)
        queryProvider.setWhereClause("ID >= :minId AND ID <= :maxId");

        // ORDER BY - obrigatório para paginação
        Map<String, Order> sortKeys = new HashMap<>();
        sortKeys.put("ID", Order.ASCENDING);
        queryProvider.setSortKeys(sortKeys);

        // Parâmetros da query (minId, maxId da partição)
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("minId", minId);
        parameters.put("maxId", maxId);

        // Construir JdbcPagingItemReader
        JdbcPagingItemReader<ColetaEntity> reader = new JdbcPagingItemReaderBuilder<ColetaEntity>()
                .name("coletaReader-partition-" + partitionNumber)
                .dataSource(iirgdDataSource)
                .queryProvider(queryProvider)
                .parameterValues(parameters)
                .pageSize(pageSize)  // Quantos registros ler por vez (= chunk-size)
                .rowMapper(new ColetaRowMapper())
                .saveState(false)  // Não salvar estado (stateless para performance)
                .build();

        log.info("JdbcPagingItemReader configured: partition={}, pageSize={}, range=[{}-{}]",
                partitionNumber, pageSize, minId, maxId);

        return reader;
    }
}

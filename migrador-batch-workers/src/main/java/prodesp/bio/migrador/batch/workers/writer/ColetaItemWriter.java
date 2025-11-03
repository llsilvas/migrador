package prodesp.bio.migrador.batch.workers.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import prodesp.bio.migrador.core.domain.model.ColetaMetadata;
import prodesp.bio.migrador.infra.client.ElasticClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writer que persiste ColetaMetadata no Elasticsearch usando Bulk API.
 * <p>
 * Processamento síncrono - a paralelização vem das partições do Spring Batch.
 * Usa Bulk API para enviar o chunk inteiro em uma única requisição HTTP.
 */
@Slf4j
@Component
public class ColetaItemWriter implements ItemWriter<ColetaMetadata> {

    @Value("${bio.elastic.biometria.write:biometria}")
    private String indexName;

    private final ElasticClient elasticClient;
    private final ObjectMapper objectMapper;

    public ColetaItemWriter(ElasticClient elasticClient, ObjectMapper objectMapper) {
        this.elasticClient = elasticClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void write(Chunk<? extends ColetaMetadata> chunk) throws Exception {
        List<? extends ColetaMetadata> items = chunk.getItems();

        if (items.isEmpty()) {
            return;
        }

        long totalStart = System.currentTimeMillis();

        // Preparar documentos para bulk request - serialize para String para evitar problemas
        long prepStart = System.currentTimeMillis();
        Map<String, String> documents = new LinkedHashMap<>();

        for (ColetaMetadata metadata : items) {
            String documentId = metadata.getIdColetaValid().toString();
            // Serializa usando nosso ObjectMapper configurado com JavaTimeModule
            String jsonDoc = objectMapper.writeValueAsString(metadata);
            documents.put(documentId, jsonDoc);
        }
        long prepElapsed = System.currentTimeMillis() - prepStart;

        // Executar bulk request usando REST API - uma única chamada HTTP para todo o chunk
        try {
            long bulkStart = System.currentTimeMillis();
            Map<String, Integer> stats = elasticClient.bulkIndexRest(indexName, documents);
            long bulkElapsed = System.currentTimeMillis() - bulkStart;

            long totalElapsed = System.currentTimeMillis() - totalStart;

            int total = stats.get("total");
            int success = stats.get("success");
            int errors = stats.get("errors");

            // ✅ LOG CONSOLIDADO: Todas métricas em uma única linha estruturada
            // Fácil de parsear com Logstash/Fluentd/Grok patterns
            double throughput = (items.size() * 1000.0) / totalElapsed;
            log.info("[ESCRITOR] Chunk enviado | índice={} | tamanho={} | sucesso={} | erros={} | " +
                     "prepMs={} | bulkMs={} | totalMs={} | throughput={}/s | médiaMs={}",
                    indexName,
                    items.size(),
                    success,
                    errors,
                    prepElapsed,
                    bulkElapsed,
                    totalElapsed,
                    String.format("%.0f", throughput),
                    String.format("%.2f", (double) totalElapsed / items.size()));

        } catch (Exception e) {
            long totalElapsed = System.currentTimeMillis() - totalStart;
            log.error("[ESCRITOR] Chunk FALHOU | índice={} | tamanho={} | totalMs={} | erro={}",
                    indexName, items.size(), totalElapsed, e.getMessage(), e);
            throw new RuntimeException("Falha ao escrever chunk no Elasticsearch", e);
        }
    }
}
package prodesp.bio.migrador.infra.client;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.get.GetResult;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prodesp.bio.migrador.infra.utils.Util;

import java.io.IOException;
import java.util.*;
import java.util.UUID;

/**
 * Wrapper simples do Elasticsearch Java Client.
 * POR QUÊ: garantir que o client nunca seja nulo e centralizar chamadas.
 * Observação: NÃO usar @Component aqui. Ele será criado via @Configuration.
 */
public class ElasticClient {

    private static final Logger LOG = LoggerFactory.getLogger(ElasticClient.class);

    private final ElasticsearchClient client;
    private final RestClient restClient;

    public ElasticClient(ElasticsearchClient client, RestClient restClient) {
        if (client == null) throw new IllegalArgumentException("ElasticsearchClient não pode ser nulo");
        if (restClient == null) throw new IllegalArgumentException("RestClient não pode ser nulo");
        this.client = client;
        this.restClient = restClient;
    }

    public Boolean indexSync(String index, UUID id, String obj) {
        return indexSync(index, id, obj, null, null);
    }

    public Boolean indexSync(String index, UUID id, String obj, String seqNo, String primaryTerm) {
        LOG.debug("$$$$ indexSync {}/{}/{}/{}", index, id, seqNo, primaryTerm);
        try {
            ObjectMapper objectMapper = Util.getObjectMapper();
            Map<String, Object> jsonMap = objectMapper.readValue(obj, Map.class);

            IndexRequest.Builder<Map<String, Object>> b = new IndexRequest.Builder<Map<String, Object>>()
                    .index(index)
                    .id(id != null ? id.toString() : null)
                    .document(jsonMap);

            if (seqNo != null) b.ifSeqNo(Long.parseLong(seqNo));
            if (primaryTerm != null) b.ifPrimaryTerm(Long.parseLong(primaryTerm));

            IndexResponse resp = client.index(b.build());
            LOG.debug("$$$$ indexSync result={} index={}/{}", resp.result(), index, id);

            return Result.Created.equals(resp.result()) || Result.Updated.equals(resp.result());
        } catch (Exception e) {
            LOG.error("error", e);
            return false;
        }
    }

    public Map<String, Object> getHealth() {
        try {
            Map<String, Object> map = new HashMap<>();
            BooleanResponse ping = client.ping();
            if (!ping.value()) throw new IOException("ping error");
            InfoResponse info = client.info();
            map.put("clusterName", info.clusterName());
            map.put("clusterUuid", info.clusterUuid());
            map.put("nodeName", info.name());
            map.put("version", info.version().number());
            map.put("build", info.version().buildType());
            map.put("isAvailable", true);
            return map;
        } catch (IOException ioe) {
            LOG.error("error", ioe);
            Map<String, Object> err = new HashMap<>();
            err.put("error", ioe.getMessage());
            return err;
        }
    }

    public UpdateResponse<Void> update(String index, String id, Script script) throws IOException {
        UpdateResponse<Void> result = null;
        for (int i = 0; i < 3; i++) {
            try {
                UpdateRequest<Void, Void> req = UpdateRequest.of(u -> u.index(index).id(id).script(script));
                LOG.debug("$$$$ update index {} / id {}", index, id);
                result = client.update(req, Void.class);
                break;
            } catch (ElasticsearchException esEx) {
                LOG.error("$$$$ ElasticsearchException update index {} / id {}", index, id, esEx);
                try { Thread.sleep(250L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        return result;
    }

    public GetResponse<GetResult> getSync(String index, String id) throws IOException {
        GetResponse<GetResult> resp = null;
        for (int i = 0; i < 5; i++) {
            try {
                LOG.debug("$$$$ get {}/{}", index, id);
                GetRequest req = GetRequest.of(g -> g.index(index).id(id));
                resp = client.get(req, GetResult.class);
                break;
            } catch (IOException | ElasticsearchException ex) {
                LOG.error("error on getSync for id {}: {}", id, ex.getMessage(), ex);
                if (i >= 4) throw new IOException(ex);
                try { Thread.sleep(100L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        return resp;
    }

    /**
     * Bulk index usando REST API de baixo nível para controle total do formato.
     * Usa NDJSON (newline-delimited JSON) que é o formato nativo do Elasticsearch Bulk API.
     *
     * @param index The index name
     * @param documents Map of document ID to JSON document string
     * @return Map com estatísticas do bulk (total, success, errors)
     * @throws IOException if the bulk operation fails
     */
    public Map<String, Integer> bulkIndexRest(String index, Map<String, String> documents) throws IOException {
        try {
            // Montar o corpo da requisição no formato NDJSON
            StringBuilder bulkBody = new StringBuilder();

            for (Map.Entry<String, String> entry : documents.entrySet()) {
                String id = entry.getKey();
                String jsonDoc = entry.getValue();

                // Linha 1: metadata da ação
                bulkBody.append("{\"index\":{\"_index\":\"").append(index).append("\",\"_id\":\"").append(id).append("\"}}\n");
                // Linha 2: documento
                bulkBody.append(jsonDoc).append("\n");
            }

            // Fazer requisição usando RestClient
            Request request = new Request("POST", "/_bulk");
            request.setEntity(new NStringEntity(bulkBody.toString(), ContentType.APPLICATION_JSON));

            LOG.debug("$$$$ bulkIndexRest: {} documents to index {}", documents.size(), index);

            Response response = restClient.performRequest(request);
            HttpEntity entity = response.getEntity();
            String responseBody = EntityUtils.toString(entity);

            // Parse response
            ObjectMapper mapper = Util.getObjectMapper();
            JsonNode root = mapper.readTree(responseBody);

            boolean hasErrors = root.get("errors").asBoolean();
            int total = documents.size();
            int errors = 0;

            if (hasErrors) {
                JsonNode items = root.get("items");
                for (JsonNode item : items) {
                    JsonNode indexNode = item.get("index");
                    if (indexNode != null && indexNode.has("error")) {
                        errors++;
                        String docId = indexNode.get("_id").asText();
                        String errorReason = indexNode.get("error").get("reason").asText();
                        LOG.error("$$$$ Bulk error for document {}: {}", docId, errorReason);
                    }
                }
            }

            int success = total - errors;
            LOG.debug("$$$$ bulkIndexRest result: total={}, success={}, errors={}", total, success, errors);

            Map<String, Integer> stats = new HashMap<>();
            stats.put("total", total);
            stats.put("success", success);
            stats.put("errors", errors);

            if (errors > 0) {
                throw new IOException("Bulk operation completed with " + errors + " errors");
            }

            return stats;

        } catch (Exception e) {
            LOG.error("Bulk index operation failed", e);
            throw new IOException("Bulk index failed", e);
        }
    }

    /**
     * @deprecated Use bulkIndexRest() instead for better control
     */
    @Deprecated
    public BulkResponse bulkIndex(String index, Map<String, String> documents) throws IOException {
        // Fallback para o método antigo se necessário
        throw new UnsupportedOperationException("Use bulkIndexRest() instead");
    }
}
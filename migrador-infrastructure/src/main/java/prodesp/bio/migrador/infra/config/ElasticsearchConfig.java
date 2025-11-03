package prodesp.bio.migrador.infra.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import prodesp.bio.migrador.infra.client.ElasticClient;
import prodesp.bio.migrador.infra.utils.Util;

import java.net.URI;

/**
 * Configuração Spring para criar UM ÚNICO pipeline de cliente ES.
 * POR QUÊ: evitar instâncias não gerenciadas e NPE em contextos assíncronos.
 */
@Configuration
@Slf4j
public class ElasticsearchConfig {

    private final String elasticUrl;
    private final String username;
    private final String password;
    private final int connectTimeoutMillis;
    private final int socketTimeoutMillis;
    private final int maxConnTotal;
    private final int maxConnPerRoute;

    public ElasticsearchConfig(
            @Value("${bio.elastic.url:http://localhost:9200}") String elasticUrl,
            @Value("${bio.elastic.connect-timeout-millis:6000}") int connectTimeoutMillis,
            @Value("${bio.elastic.socket-timeout-millis:60000}") int socketTimeoutMillis,
            @Value("${bio.elastic.user:elastic}") String username,
            @Value("${bio.elastic.password:password}") String password,
            @Value("${bio.elastic.max-conn-total:200}") int maxConnTotal,
            @Value("${bio.elastic.max-conn-per-route:100}") int maxConnPerRoute) {
        this.elasticUrl = elasticUrl;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.socketTimeoutMillis = socketTimeoutMillis;
        this.username = username;
        this.password = password;
        this.maxConnTotal = maxConnTotal;
        this.maxConnPerRoute = maxConnPerRoute;
    }

    @Bean(destroyMethod = "close")
    RestClient restClient() {
        URI uri = URI.create(elasticUrl);
        RestClientBuilder builder = RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()))
                .setRequestConfigCallback(req -> req
                        .setConnectTimeout(connectTimeoutMillis)
                        .setSocketTimeout(socketTimeoutMillis))
                .setHttpClientConfigCallback(http -> {
                    CredentialsProvider cp = new BasicCredentialsProvider();
                    cp.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));

                    // Otimização para Virtual Threads:
                    // - maxConnTotal: total de conexões simultâneas ao cluster
                    // - maxConnPerRoute: conexões simultâneas por nó do cluster
                    // Com virtual threads, podemos ter muito mais partições executando em paralelo
                    return http
                            .setDefaultCredentialsProvider(cp)
                            .setMaxConnTotal(maxConnTotal)
                            .setMaxConnPerRoute(maxConnPerRoute);
                });

        log.info("RestClient configured: maxConnTotal={}, maxConnPerRoute={}", maxConnTotal, maxConnPerRoute);
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        // Usa o mesmo ObjectMapper do projeto para (de)serialização consistente.
        return new RestClientTransport(restClient, new JacksonJsonpMapper(Util.getObjectMapper()));
    }

    @Bean
    ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }

    @Bean(name = "elasticMetadata")
    ElasticClient elasticClientWrapper(ElasticsearchClient esClient, RestClient restClient) {
        ElasticClient wrapper = new ElasticClient(esClient, restClient);
        // Falha cedo se conexão/autenticação estiver incorreta
        wrapper.getHealth().forEach((k, v) -> log.info("ELASTIC BIO '{}' = '{}'", k, v));
        return wrapper;
    }
}
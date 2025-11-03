package prodesp.bio.migrador.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "spring.data.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisConfiguration {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:#{null}}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    /**
     * RedissonClient - Cliente Redisson para locks distribuídos avançados.
     *
     * Vantagens sobre RedisTemplate para locks:
     * - RLock reentrant (mesma thread pode adquirir múltiplas vezes)
     * - Watchdog automático (renova lease durante processamento)
     * - Retry automático com exponential backoff
     * - Fair locks (FIFO ordering)
     * - MultiLock / RedLock (para multi-node Redis)
     */
    @Bean(destroyMethod = "shutdown")
    @Primary
    public RedissonClient redissonClient() {
        Config config = new Config();

        // Configuração Single Server (para cluster, usar clusterServersConfig)
        String address = String.format("redis://%s:%d", redisHost, redisPort);

        var singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase)
                // Connection pool
                .setConnectionPoolSize(64)      // Pool de conexões (padrão: 64)
                .setConnectionMinimumIdleSize(24) // Mínimo idle (padrão: 24)
                // Timeouts
                .setConnectTimeout(10000)       // Connect timeout: 10s
                .setTimeout(3000)               // Command timeout: 3s
                .setRetryAttempts(3)            // Retry 3x em caso de falha
                .setRetryInterval(1500)         // Intervalo entre retries: 1.5s
                // Ping
                .setPingConnectionInterval(30000) // Ping a cada 30s para manter conexão
                .setKeepAlive(true);

        // Configurar senha apenas se fornecida (Redis local geralmente não tem senha)
        if (redisPassword != null && !redisPassword.isBlank()) {
            singleServerConfig.setPassword(redisPassword);
            log.info("Redis password configured");
        } else {
            log.info("Redis running without password (local development)");
        }

        // Lock watchdog timeout (default: 30s)
        // Se operação levar > 30s, watchdog renova automaticamente
        config.setLockWatchdogTimeout(60000L); // Aumentar para 60s (batch pode demorar)

        // Codec (serialização)
        // Usar FstCodec para performance ou JsonJacksonCodec para debug
        // config.setCodec(new org.redisson.codec.FstCodec());

        RedissonClient client = Redisson.create(config);

        log.info("=== RedissonClient configured successfully ===");
        log.info("Redis Address: {}", address);
        log.info("Lock Watchdog Timeout: {}ms", config.getLockWatchdogTimeout());
        log.info("Connection Pool Size: 64, Min Idle: 24");

        return client;
    }

    /**
     * RedissonConnectionFactory - Para integração com Spring Data Redis.
     * Permite usar Redisson como backend do RedisTemplate.
     */
    @Bean
    public RedissonConnectionFactory redissonConnectionFactory(RedissonClient redissonClient) {
        return new RedissonConnectionFactory(redissonClient);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedissonConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Configurar ObjectMapper para Jackson
        ObjectMapper mapper = createObjectMapper();

        // Serializador JSON usando o ObjectMapper configurado
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(mapper);

        // Configurar serializadores
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();

        log.info("RedisTemplate configured successfully");
        return template;
    }

    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        ObjectMapper mapper = createObjectMapper();
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(mapper);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(serializer)
                )
                .disableCachingNullValues()
                .prefixCacheNameWith("migrador:cache:");
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // Cache específico para partições
        cacheConfigurations.put("partitions",
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMinutes(30))
                        .disableCachingNullValues()
                        .prefixCacheNameWith("migrador:partitions:"));

        // Cache para estatísticas
        cacheConfigurations.put("statistics",
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMinutes(5))
                        .disableCachingNullValues()
                        .prefixCacheNameWith("migrador:stats:"));

        // Cache para dados de referência (postos, órgãos, etc)
        cacheConfigurations.put("reference-data",
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofHours(24))
                        .disableCachingNullValues()
                        .prefixCacheNameWith("migrador:ref:"));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfiguration())
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .build();
    }

    /**
     * Cria e configura ObjectMapper para serialização Redis
     */
    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Registrar módulo para suporte a java.time (LocalDateTime, Instant, etc)
        mapper.registerModule(new JavaTimeModule());

        // Desabilitar timestamps numéricos para datas (usar ISO-8601)
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Configurar validador de tipos polimórficos (para Spring Boot 3.x)
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();

        // Ativar tipagem padrão para preservar tipos de classes
        mapper.activateDefaultTyping(
                ptv,
                ObjectMapper.DefaultTyping.NON_FINAL
        );

        return mapper;
    }
}
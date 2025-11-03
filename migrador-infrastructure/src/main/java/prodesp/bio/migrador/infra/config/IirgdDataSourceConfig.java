package prodesp.bio.migrador.infra.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Configuração do DataSource para o banco IIRGD (SQL Server).
 *
 * Este é um datasource secundário, usado apenas para leitura de dados
 * na migração. O datasource primário (H2) é usado pelo Spring Batch
 * para metadata (JobRepository).
 *
 * IMPORTANTE: Usar @ConfigurationProperties para vincular com application.yml
 */
@Slf4j
@Configuration
public class IirgdDataSourceConfig {

    /**
     * DataSource IIRGD - Banco de origem (SQL Server).
     *
     * Configurado via application.yml usando prefix "iirgd.datasource".
     * Pool otimizado para leitura paralela com virtual threads.
     */
    @Bean(name = "iirgdDataSource")
    @ConfigurationProperties(prefix = "iirgd.datasource.hikari")
    public DataSource iirgdDataSource() {
        // Configuração base do HikariCP
        HikariConfig config = new HikariConfig();

        // Propriedades básicas (virão do application.yml via @ConfigurationProperties)
        config.setJdbcUrl(getJdbcUrl());
        config.setUsername(getUsername());
        config.setPassword(getPassword());
        config.setDriverClassName(getDriverClassName());

        // As propriedades do pool (maximum-pool-size, etc) serão injetadas
        // automaticamente pelo @ConfigurationProperties

        // Configurações adicionais para SQL Server
        config.addDataSourceProperty("applicationName", "Migrador-IIRGD");
        config.addDataSourceProperty("loginTimeout", "30");

        // Pool settings (defaults - podem ser sobrescritos pelo YAML)
        if (config.getMaximumPoolSize() == 0) {
            config.setMaximumPoolSize(60);
        }
        if (config.getMinimumIdle() == 0) {
            config.setMinimumIdle(20);
        }

        HikariDataSource dataSource = new HikariDataSource(config);

        log.info("=== IIRGD DataSource configured ===");
        log.info("JDBC URL: {}", maskPassword(config.getJdbcUrl()));
        log.info("Pool: max={}, min={}", config.getMaximumPoolSize(), config.getMinimumIdle());
        log.info("Driver: {}", config.getDriverClassName());

        return dataSource;
    }

    /**
     * Helper methods para obter propriedades do application.yml.
     * Spring Boot injeta via @Value ou Environment.
     */
    @org.springframework.beans.factory.annotation.Value("${iirgd.datasource.jdbc-url}")
    private String jdbcUrl;

    @org.springframework.beans.factory.annotation.Value("${iirgd.datasource.username}")
    private String username;

    @org.springframework.beans.factory.annotation.Value("${iirgd.datasource.password}")
    private String password;

    @org.springframework.beans.factory.annotation.Value("${iirgd.datasource.driver-class-name}")
    private String driverClassName;

    private String getJdbcUrl() {
        return jdbcUrl;
    }

    private String getUsername() {
        return username;
    }

    private String getPassword() {
        return password;
    }

    private String getDriverClassName() {
        return driverClassName;
    }

    private String maskPassword(String url) {
        // Simples máscara para não logar senha na URL
        return url.replaceAll("password=[^;]+", "password=***");
    }
}

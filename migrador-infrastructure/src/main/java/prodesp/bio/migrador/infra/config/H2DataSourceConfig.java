package prodesp.bio.migrador.infra.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Configuração do DataSource H2 para Spring Batch metadata.
 *
 * Este é o datasource PRIMÁRIO, usado pelo Spring Batch para armazenar
 * metadata de jobs (JobRepository, JobExplorer, etc).
 *
 * IMPORTANTE: Marcado como @Primary para garantir que o Spring Batch
 * use este datasource e não o SQL Server.
 */
@Slf4j
@Configuration
public class H2DataSourceConfig {

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}")
    private String jdbcUrl;

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.username}")
    private String username;

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.password:#{null}}")
    private String password;

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.driver-class-name}")
    private String driverClassName;

    /**
     * DataSource H2 - Banco de metadata do Spring Batch.
     *
     * Configurado via application.yml usando prefix "spring.datasource".
     * Marcado como @Primary para ser o datasource padrão da aplicação.
     */
    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource h2DataSource() {
        // Configuração base do HikariCP
        HikariConfig config = new HikariConfig();

        // Propriedades básicas (virão do application.yml)
        config.setJdbcUrl(getJdbcUrl());
        config.setDriverClassName(getDriverClassName());
        config.setUsername(getUsername());
        config.setPassword(getPassword());

        // Pool settings (defaults - podem ser sobrescritos pelo YAML)
        if (config.getMaximumPoolSize() == 0) {
            config.setMaximumPoolSize(10);
        }
        if (config.getMinimumIdle() == 0) {
            config.setMinimumIdle(5);
        }

        HikariDataSource dataSource = new HikariDataSource(config);

        log.info("=== H2 DataSource configured (PRIMARY) ===");
        log.info("JDBC URL: {}", config.getJdbcUrl());
        log.info("Pool: max={}, min={}", config.getMaximumPoolSize(), config.getMinimumIdle());
        log.info("Driver: {}", config.getDriverClassName());
        log.info("Purpose: Spring Batch metadata (JobRepository)");

        return dataSource;
    }

    private String getJdbcUrl() {
        return jdbcUrl;
    }

    private String getUsername() {
        return username;
    }

    private String getPassword() {
        return password != null ? password : "";
    }

    private String getDriverClassName() {
        return driverClassName;
    }
}

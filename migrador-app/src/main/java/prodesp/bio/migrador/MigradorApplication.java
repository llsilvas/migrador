package prodesp.bio.migrador;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
    "prodesp.bio.migrador.core",
    "prodesp.bio.migrador.infra",
    "prodesp.bio.migrador.batch.control",
    "prodesp.bio.migrador.batch.workers",
    "prodesp.bio.migrador.controller"
})
@EnableScheduling
public class MigradorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MigradorApplication.class, args);
    }
}

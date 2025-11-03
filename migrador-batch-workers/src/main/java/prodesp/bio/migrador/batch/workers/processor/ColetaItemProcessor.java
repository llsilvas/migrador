package prodesp.bio.migrador.batch.workers.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import prodesp.bio.migrador.core.domain.model.*;
import prodesp.bio.migrador.core.port.DeadLetterQueuePort;

import java.util.Base64;
import java.util.UUID;

/**
 * Processor que transforma ColetaEntity em ColetaMetadata para Elasticsearch.
 *
 * Aplica regras de negócio, validações e transformações necessárias.
 */
@Slf4j
@Component
public class ColetaItemProcessor implements ItemProcessor<ColetaEntity, ColetaMetadata> {

    private final DeadLetterQueuePort dlq;
    private final ObjectMapper objectMapper;

    public ColetaItemProcessor(DeadLetterQueuePort dlq, ObjectMapper objectMapper) {
        this.dlq = dlq;
        this.objectMapper = objectMapper;
    }

    @Override
    public ColetaMetadata process(ColetaEntity item) throws Exception {
        try {
            // Validações básicas
            if (item.getIdColetaValid() == null || item.getIdColetaValid().isBlank()) {
                log.warn("Skipping record with null/empty idColetaValid: id={}", item.getId());
                return null; // Retornar null faz o Spring Batch pular o item
            }

            // Construir metadata
            ColetaMetadata metadata = ColetaMetadata.builder()
                    .idColetaValid(UUID.fromString(item.getIdColetaValid()))
                    .build();

            // Popular sub-objetos de metadata
            populateDadosColeta(metadata, item);
//            populateDadoBiometrico(metadata, item);

            log.trace("Processed record: id={}, idColetaValid={}",
                    item.getId(), item.getIdColetaValid());

            return metadata;

        } catch (Exception e) {
            log.error("Error processing record id={}: {}",
                    item.getId(), e.getMessage(), e);

            // Adicionar na DLQ e re-throw para que o Spring Batch conte como skip
            dlq.addToDeadLetterQueue(item, e);
            throw e;
        }
    }

    private void populateDadosColeta(ColetaMetadata metadata, ColetaEntity entity) {
        DadosColetaMetadata dadosColeta = DadosColetaMetadata.builder()
                .cpf(entity.getCpf())
                .rg(entity.getRg())
                .dataColeta(entity.getDataColeta())
                .tipoColeta(entity.getTipoColeta())
                .build();

        metadata.setDadosColeta(dadosColeta);
    }

//    private void populateDadoBiometrico(ColetaMetadata metadata, ColetaEntity entity) {
//        if (entity.getImagemDigital() != null && entity.getImagemDigital().length > 0) {
//            DadoBiometricoMetadata dadoBiometrico = DadoBiometricoMetadata.builder()
//                    .imagemBase64(Base64.getEncoder().encodeToString(entity.getImagemDigital()))
//                    .tamanhoBytes(entity.getImagemDigital().length)
//                    .build();
//
//            metadata.setDadoBiometrico(dadoBiometrico);
//        }
//    }
}
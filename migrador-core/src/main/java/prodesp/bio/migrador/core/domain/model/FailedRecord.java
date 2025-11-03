package prodesp.bio.migrador.core.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedRecord implements Serializable {
    private String idColetaValid;
    private ColetaEntity originalData;
    private String errorMessage;
    private String errorClass;
    private String stackTrace;
    private Instant timestamp;
    private Integer retryCount;
    private Integer partitionNumber;
    private Long jobExecutionId;
}

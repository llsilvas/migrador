package prodesp.bio.migrador.core.domain.model;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColetaEntity implements Serializable {
    private Long id;
    private String idColetaValid;
    private String cpf;
    private String rg;
    private LocalDateTime dataColeta;
    private byte[] imagemDigital;
    private String tipoColeta;
    private Integer partitionNumber;
}

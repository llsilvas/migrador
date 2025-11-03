package prodesp.bio.migrador.core.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionInfo implements Serializable {
    private Integer partitionNumber;
    private Long minId;
    private Long maxId;
    private Long estimatedRecords;
}

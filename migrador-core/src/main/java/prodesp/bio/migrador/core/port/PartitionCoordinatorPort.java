package prodesp.bio.migrador.core.port;


import java.util.Map;

public interface PartitionCoordinatorPort {
    void registerPartition(int partitionNumber, long totalRecords);
    void startPartition(int partitionNumber);
    void completePartition(int partitionNumber);
    void failPartition(int partitionNumber);
    void updateProgress(int partitionNumber, long processed, long total);
    Map<Integer, PartitionStatus> getAllPartitionsStatus();
    boolean areAllPartitionsComplete();
    void clearPartitionMetadata();

    enum PartitionStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }
}

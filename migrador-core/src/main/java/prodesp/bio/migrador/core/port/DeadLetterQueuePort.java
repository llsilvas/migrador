package prodesp.bio.migrador.core.port;


import prodesp.bio.migrador.core.domain.model.ColetaEntity;
import prodesp.bio.migrador.core.domain.model.FailedRecord;

import java.util.List;
import java.util.Map;

public interface DeadLetterQueuePort {
    void addToDeadLetterQueue(ColetaEntity failedRecord, Exception error);
    List<FailedRecord> getFailedRecords(int offset, int limit);
    FailedRecord popFailedRecord();
    long getFailedRecordsCount();
    Map<String, Long> getDlqStatistics();
    void clearDeadLetterQueue();
}
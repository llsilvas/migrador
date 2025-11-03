package prodesp.bio.migrador.core.port;


import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public interface PartitionLockPort {
    boolean tryLock(int partitionNumber, long waitTime, TimeUnit unit);
    void unlock(int partitionNumber);
    <T> T executeWithLock(int partitionNumber, Supplier<T> action, long waitTime, TimeUnit unit);
    void forceUnlock(int partitionNumber);
}

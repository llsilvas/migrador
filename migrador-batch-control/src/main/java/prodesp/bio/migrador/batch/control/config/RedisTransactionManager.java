package prodesp.bio.migrador.batch.control.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.ResourceTransactionManager;

import java.io.Serializable;

@Slf4j
@RequiredArgsConstructor
public class RedisTransactionManager extends AbstractPlatformTransactionManager
        implements ResourceTransactionManager {

    private static final long serialVersionUID = 1L;

    private final RedisConnectionFactory connectionFactory;

    @Override
    protected Object doGetTransaction() throws TransactionException {
        log.trace("Getting Redis transaction object");
        RedisTransactionObject txObject = new RedisTransactionObject();
        txObject.setConnectionFactory(connectionFactory);
        return txObject;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition)
            throws TransactionException {
        log.trace("Beginning Redis transaction");
        RedisTransactionObject txObject = (RedisTransactionObject) transaction;

        try {
            txObject.getConnection().multi();
            log.trace("Redis MULTI command executed");
        } catch (Exception e) {
            log.error("Failed to begin Redis transaction", e);
            throw new TransactionException("Could not begin Redis transaction", e) {};
        }
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        log.trace("Committing Redis transaction");
        RedisTransactionObject txObject = (RedisTransactionObject) status.getTransaction();

        try {
            txObject.getConnection().exec();
            log.trace("Redis EXEC command executed - transaction committed");
        } catch (Exception e) {
            log.error("Failed to commit Redis transaction", e);
            throw new TransactionException("Could not commit Redis transaction", e) {};
        }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        log.trace("Rolling back Redis transaction");
        RedisTransactionObject txObject = (RedisTransactionObject) status.getTransaction();

        try {
            txObject.getConnection().discard();
            log.trace("Redis DISCARD command executed - transaction rolled back");
        } catch (Exception e) {
            log.error("Failed to rollback Redis transaction", e);
            throw new TransactionException("Could not rollback Redis transaction", e) {};
        }
    }

    @Override
    public Object getResourceFactory() {
        return connectionFactory;
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        log.trace("Cleaning up Redis transaction resources");
        RedisTransactionObject txObject = (RedisTransactionObject) transaction;
        txObject.closeConnection();
    }

    /**
     * Redis transaction object holding connection state.
     */
    private static class RedisTransactionObject implements Serializable {
        private static final long serialVersionUID = 1L;

        private RedisConnectionFactory connectionFactory;
        private transient org.springframework.data.redis.connection.RedisConnection connection;

        public void setConnectionFactory(RedisConnectionFactory connectionFactory) {
            this.connectionFactory = connectionFactory;
        }

        public org.springframework.data.redis.connection.RedisConnection getConnection() {
            if (connection == null) {
                connection = connectionFactory.getConnection();
            }
            return connection;
        }

        public void closeConnection() {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    // Log mas não lança exceção no cleanup
                } finally {
                    connection = null;
                }
            }
        }
    }
}


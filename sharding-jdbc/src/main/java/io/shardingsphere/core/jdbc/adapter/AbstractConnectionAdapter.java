/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.core.jdbc.adapter;

import com.google.common.base.Preconditions;
import io.shardingsphere.core.constant.TCLType;
import io.shardingsphere.core.constant.TransactionType;
import io.shardingsphere.core.hint.HintManagerHolder;
import io.shardingsphere.core.jdbc.core.transaction.WeakXaTransactionManager;
import io.shardingsphere.core.jdbc.unsupported.AbstractUnsupportedOperationConnection;
import io.shardingsphere.core.routing.router.masterslave.MasterVisitedManager;
import io.shardingsphere.core.transaction.TransactionContext;
import io.shardingsphere.core.transaction.TransactionContextHolder;
import io.shardingsphere.core.transaction.event.TransactionEvent;
import io.shardingsphere.core.transaction.event.TransactionEventFactory;
import io.shardingsphere.core.transaction.event.WeakXaTransactionEvent;
import io.shardingsphere.core.util.EventBusInstance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Adapter for {@code Connection}.
 * 
 * @author zhangliang
 */
public abstract class AbstractConnectionAdapter extends AbstractUnsupportedOperationConnection {
    
    private final Collection<Connection> cachedConnections = new CopyOnWriteArrayList<>();
    
    private boolean autoCommit = true;
    
    private boolean readOnly = true;
    
    private boolean closed;
    
    private int transactionIsolation = TRANSACTION_READ_UNCOMMITTED;
    
    /**
     * Get database connection.
     *
     * @param dataSourceName data source name
     * @return database connection
     * @throws SQLException SQL exception
     */
    public final Connection getConnection(final String dataSourceName) throws SQLException {
        TransactionContextHolder.set(new TransactionContext(new WeakXaTransactionManager(), TransactionType.XA, WeakXaTransactionEvent.class));
        DataSource dataSource = getDataSourceMap().get(dataSourceName);
        Preconditions.checkState(null != dataSource, "Missing the data source name: '%s'", dataSourceName);
        Connection result = dataSource.getConnection();
        cachedConnections.add(result);
        replayMethodsInvocation(result);
        return result;
    }
    
    protected abstract Map<String, DataSource> getDataSourceMap();
    
    protected void removeCache(final Connection connection) {
        cachedConnections.remove(connection);
    }
    
    @Override
    public final boolean getAutoCommit() {
        return autoCommit;
    }
    
    @Override
    public final void setAutoCommit(final boolean autoCommit) {
        this.autoCommit = autoCommit;
        TransactionContextHolder.set(new TransactionContext(new WeakXaTransactionManager(), TransactionType.XA, WeakXaTransactionEvent.class));
        recordMethodInvocation(Connection.class, "setAutoCommit", new Class[] {boolean.class}, new Object[] {autoCommit});
        EventBusInstance.getInstance().post(buildTransactionEvent(TCLType.BEGIN));
    }
    
    @Override
    public final void commit() {
        EventBusInstance.getInstance().post(buildTransactionEvent(TCLType.COMMIT));
    }
    
    @Override
    public final void rollback() {
        EventBusInstance.getInstance().post(buildTransactionEvent(TCLType.ROLLBACK));
    }
    
    @Override
    public void close() throws SQLException {
        closed = true;
        HintManagerHolder.clear();
        MasterVisitedManager.clear();
        Collection<SQLException> exceptions = new LinkedList<>();
        for (Connection each : cachedConnections) {
            try {
                each.close();
            } catch (final SQLException ex) {
                exceptions.add(ex);
            }
        }
        throwSQLExceptionIfNecessary(exceptions);
    }
    
    @Override
    public final boolean isClosed() {
        return closed;
    }
    
    @Override
    public final boolean isReadOnly() {
        return readOnly;
    }
    
    @Override
    public final void setReadOnly(final boolean readOnly) throws SQLException {
        this.readOnly = readOnly;
        recordMethodInvocation(Connection.class, "setReadOnly", new Class[] {boolean.class}, new Object[] {readOnly});
        for (Connection each : cachedConnections) {
            each.setReadOnly(readOnly);
        }
    }
    
    @Override
    public final int getTransactionIsolation() throws SQLException {
        if (cachedConnections.isEmpty()) {
            return transactionIsolation;
        }
        return cachedConnections.iterator().next().getTransactionIsolation();
    }
    
    @Override
    public final void setTransactionIsolation(final int level) throws SQLException {
        transactionIsolation = level;
        recordMethodInvocation(Connection.class, "setTransactionIsolation", new Class[] {int.class}, new Object[] {level});
        for (Connection each : cachedConnections) {
            each.setTransactionIsolation(level);
        }
    }
    
    // ------- Consist with MySQL driver implementation -------
    
    @Override
    public SQLWarning getWarnings() {
        return null;
    }
    
    @Override
    public void clearWarnings() {
    }
    
    @Override
    public final int getHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }
    
    @Override
    public final void setHoldability(final int holdability) {
    }
    
    private TransactionEvent buildTransactionEvent(final TCLType tclType) {
        TransactionEvent result = TransactionEventFactory.create(tclType);
        if (result instanceof WeakXaTransactionEvent) {
            WeakXaTransactionEvent weakXaTransactionEvent = (WeakXaTransactionEvent) result;
            weakXaTransactionEvent.setCachedConnections(cachedConnections);
            weakXaTransactionEvent.setAutoCommit(autoCommit);
        }
        return result;
    }
}

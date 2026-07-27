package com.thaap.payments.simulation;

import javax.sql.DataSource;
import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class RiskSimulationBatchInfrastructureConfiguration
        extends JdbcDefaultBatchConfiguration {

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;

    public RiskSimulationBatchInfrastructureConfiguration(DataSource dataSource,
            PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
    }

    @Override
    protected DataSource getDataSource() {
        return dataSource;
    }

    @Override
    protected PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }
}

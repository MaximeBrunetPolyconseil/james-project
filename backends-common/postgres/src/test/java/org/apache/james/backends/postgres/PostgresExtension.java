/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.backends.postgres;

import org.apache.james.GuiceModuleTestExtension;
import org.apache.james.backends.postgres.utils.PostgresExecutor;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.google.inject.Module;
import com.google.inject.util.Modules;

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Mono;

public class PostgresExtension implements GuiceModuleTestExtension {
    private final PostgresModule postgresModule;
    private final boolean rlsEnabled;
    private PostgresConfiguration postgresConfiguration;
    private PostgresExecutor postgresExecutor;
    private PostgresqlConnectionFactory connectionFactory;

    public PostgresExtension(PostgresModule postgresModule, boolean rlsEnabled) {
        this.postgresModule = postgresModule;
        this.rlsEnabled = rlsEnabled;
    }

    public PostgresExtension(PostgresModule postgresModule) {
        this(postgresModule, false);
    }

    public PostgresExtension(boolean rlsEnabled) {
        this(PostgresModule.EMPTY_MODULE, rlsEnabled);
    }

    public PostgresExtension() {
        this(false);
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        if (!DockerPostgresSingleton.SINGLETON.isRunning()) {
            DockerPostgresSingleton.SINGLETON.start();
        }
        initPostgresSession();
    }

    private void initPostgresSession() {
        postgresConfiguration = PostgresConfiguration.builder()
            .url(String.format("postgresql://%s:%s@%s:%d", PostgresFixture.Database.DB_USER, PostgresFixture.Database.DB_PASSWORD, getHost(), getMappedPort()))
            .databaseName(PostgresFixture.Database.DB_NAME)
            .databaseSchema(PostgresFixture.Database.SCHEMA)
            .rlsEnabled(rlsEnabled)
            .build();

        PostgresqlConnectionFactory connectionFactory = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
            .host(postgresConfiguration.getUrl().getHost())
            .port(postgresConfiguration.getUrl().getPort())
            .username(postgresConfiguration.getCredential().getUsername())
            .password(postgresConfiguration.getCredential().getPassword())
            .database(postgresConfiguration.getDatabaseName())
            .schema(postgresConfiguration.getDatabaseSchema())
            .build());

        postgresExecutor = new PostgresExecutor(connectionFactory.create()
            .cache()
            .cast(Connection.class));
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        disposePostgresSession();
    }

    private void disposePostgresSession() {
        postgresExecutor.dispose().block();
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        initTablesAndIndexes();
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        resetSchema();
    }

    public void restartContainer() {
        DockerPostgresSingleton.SINGLETON.stop();
        DockerPostgresSingleton.SINGLETON.start();
        initPostgresSession();
    }

    @Override
    public Module getModule() {
        return Modules.combine(binder -> binder.bind(PostgresConfiguration.class)
            .toInstance(postgresConfiguration));
    }

    public String getHost() {
        return DockerPostgresSingleton.SINGLETON.getHost();
    }

    public Integer getMappedPort() {
        return DockerPostgresSingleton.SINGLETON.getMappedPort(PostgresFixture.PORT);
    }

    public Mono<Connection> getConnection() {
        return postgresExecutor.connection();
    }

    public PostgresExecutor getPostgresExecutor() {
        return postgresExecutor;
    }
    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    private void initTablesAndIndexes() {
        PostgresTableManager postgresTableManager = new PostgresTableManager(postgresExecutor, postgresModule, postgresConfiguration.rlsEnabled());
        postgresTableManager.initializeTables().block();
        postgresTableManager.initializeTableIndexes().block();
    }

    private void resetSchema() {
        getConnection()
            .flatMapMany(connection -> Mono.from(connection.createStatement("DROP SCHEMA " + PostgresFixture.Database.SCHEMA + " CASCADE").execute())
                .then(Mono.from(connection.createStatement("CREATE SCHEMA " + PostgresFixture.Database.SCHEMA).execute()))
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
            .collectList()
            .block();
    }
}

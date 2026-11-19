/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package example.dice.backends;

import org.drivine.autoconfigure.EnableDrivine;
import org.drivine.manager.GraphObjectManagerFactory;
import org.drivine.manager.PersistenceManager;
import org.drivine.manager.PersistenceManagerFactory;
import org.drivine.manager.StatelessGraphObjectManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one piece the graph backend adds: the Neo4j connection DICE's graph store is built on.
 *
 * <p>DICE builds the Neo4j proposition store itself, but it needs two Drivine objects that the
 * application has to supply: a {@link PersistenceManager} for queries and a
 * {@link StatelessGraphObjectManager} for loading and saving objects. Both come from the
 * datasource named {@value #DATASOURCE}, configured under {@code database.datasources.neo}.
 *
 * <p>This class loads only when {@code embabel.dice.store.type=graph}. On the in-memory store
 * there is no Drivine setup at all, and these beans could not be built.
 */
@Configuration(proxyBeanMethods = false)
@EnableDrivine
@ConditionalOnProperty(prefix = "embabel.dice.store", name = "type", havingValue = "graph")
class GraphStoreConfiguration {

    /** The Drivine datasource the graph store uses. */
    static final String DATASOURCE = "neo";

    /**
     * @param factory Drivine's factory, switched on by {@code @EnableDrivine}
     * @return the persistence manager for the Neo4j datasource
     */
    @Bean
    PersistenceManager persistenceManager(PersistenceManagerFactory factory) {
        return factory.get(DATASOURCE);
    }

    /**
     * @param factory Drivine's factory, switched on by {@code @EnableDrivine}
     * @return the object manager for the Neo4j datasource
     */
    @Bean
    StatelessGraphObjectManager graphObjectManager(GraphObjectManagerFactory factory) {
        return factory.stateless(DATASOURCE);
    }
}

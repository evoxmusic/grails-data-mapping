/* Copyright (C) 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.gorm.neo4j

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.query.Query
import org.neo4j.cypher.javacompat.ExecutionResult
import org.neo4j.graphdb.ResourceIterator
import org.neo4j.helpers.collection.IteratorUtil

import static org.grails.datastore.mapping.query.Query.*
import org.neo4j.cypher.javacompat.ExecutionEngine

/**
 * perform criteria queries on a Neo4j backend
 *
 * @author Stefan Armbruster <stefan@armbruster-it.de>
 */
@CompileStatic
@Slf4j
class Neo4jQuery extends Query {

    protected Neo4jQuery(Session session, PersistentEntity entity) {
        super(session, entity)
    }

    @Override
    protected List executeQuery(PersistentEntity entity, Junction criteria) {

        def returnColumns = buildReturnColumns(entity)
        def params = [:] as Map<String,Object>
        def conditions = buildConditions(criteria, params)
        def cypher = """MATCH (n:$entity.discriminator) ${conditions ? "WHERE " + conditions : " "}
RETURN $returnColumns"""

        if (!orderBy.empty) {
            cypher += " ORDER BY "
            cypher += orderBy.collect { Order order -> "n.${order.property} $order.direction" }.join(", ")
        }

        if (offset!=0) {
            cypher += " SKIP {__skip__}"
            params["__skip__"] = offset
        }

        if (max!=-1) {
            cypher += " LIMIT {__limit__}"
            params["__limit__"] = max
        }

        log.info "running cypher : $cypher"
        log.info "   with params : $params"

        ExecutionResult executionResult = executionEngine.execute(cypher, params)
        if (projections.projectionList.empty) {
            return executionResult.collect { Map map ->
                Neo4jUtils.unmarshall(map, entity)
            }
        } else {

            executionResult.collect { Map<String, Object> row ->
                executionResult.columns().collect {
                    row[it]
                }
            }.flatten() as List
            /*for (Map<String, Object> row in executionResult) {

            }
            ResourceIterator<Map<String, Object>> iterator = executionResult.iterator()
            Map firstRow = (Map) (iterator.next())
            assert !iterator.hasNext()
            executionResult.columns().collect {
                firstRow[it]
            }*/
        }
    }

    def buildReturnColumns(PersistentEntity entity) {
        if (projections.projectionList.empty) {
            Neo4jUtils.cypherReturnColumnsForType(entity)
        } else {
            projections.projectionList
                    .collect { Projection projection -> buildProjection(projection) }
                    .join(", ")
        }
    }

    def buildProjection(Projection projection) {
        switch (projection) {
            case CountProjection:
                return "count(*)"
                break
            case CountDistinctProjection:
                def propertyName =  ((PropertyProjection)projection).propertyName
                return "count( distinct n.${propertyName})"
                break
            case MinProjection:
                def propertyName =  ((PropertyProjection)projection).propertyName
                return "min(n.${propertyName})"
                break
            case MaxProjection:
                def propertyName = ((PropertyProjection) projection).propertyName
                return "max(n.${propertyName})"
                break
            case PropertyProjection:
                def propertyName = ((PropertyProjection) projection).propertyName
                return "n.${propertyName}"
                break

            default:
                throw new UnsupportedOperationException("projection ${projection.class}")
        }
    }

    def buildConditions(Criterion criterion, Map params) {

        switch (criterion) {

            case PropertyCriterion:
                def pnc = ((PropertyCriterion)criterion)
                params[pnc.property] = Neo4jUtils.mapToAllowedNeo4jType(pnc.value, entity.mappingContext)
                def rhs
                def lhs
                def operator

                switch (pnc) {
                    case Equals:
                        lhs = "n.$pnc.property"
                        operator = "="
                        rhs = "{$pnc.property}"
                        break
                    case IdEquals:
                        lhs = "id(n)"
                        operator = "="
                        rhs = "{$pnc.property}"
                        break
                    case Like:
                        lhs = "n.$pnc.property"
                        operator = "=~"
                        rhs = "{$pnc.property}"
                        params[pnc.property] = pnc.value.toString().replaceAll("%", ".*")
                        break
                    default:
                        throw new UnsupportedOperationException()
                }

                return "$lhs$operator$rhs"
                break
            case Conjunction:
            case Disjunction:
                def inner = ((Junction)criterion).criteria
                        .collect { Criterion it -> buildConditions(it, params)}
                        .join( criterion instanceof Conjunction ? ' AND ' : ' OR ')
                return inner ? "( $inner )" : inner
                break

            default:
                throw new UnsupportedOperationException()
        }
    }

    ExecutionEngine getExecutionEngine() {
        session.nativeInterface as ExecutionEngine
    }
}



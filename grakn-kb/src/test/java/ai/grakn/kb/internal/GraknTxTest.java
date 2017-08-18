/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.kb.internal;

import ai.grakn.Grakn;
import ai.grakn.GraknTx;
import ai.grakn.GraknSession;
import ai.grakn.GraknTxType;
import ai.grakn.concept.Attribute;
import ai.grakn.concept.AttributeType;
import ai.grakn.concept.Entity;
import ai.grakn.concept.EntityType;
import ai.grakn.concept.Label;
import ai.grakn.concept.RelationshipType;
import ai.grakn.concept.SchemaConcept;
import ai.grakn.concept.Role;
import ai.grakn.concept.RuleType;
import ai.grakn.exception.GraphOperationException;
import ai.grakn.exception.InvalidGraphException;
import ai.grakn.kb.internal.concept.EntityTypeImpl;
import ai.grakn.kb.internal.structure.Shard;
import ai.grakn.util.ErrorMessage;
import ai.grakn.util.Schema;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.VerificationException;
import org.hamcrest.core.IsInstanceOf;
import org.junit.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class GraknTxTest extends GraphTestBase {

    @Test
    public void whenGettingConceptById_ReturnTheConcept(){
        EntityType entityType = graknGraph.putEntityType("test-name");
        assertEquals(entityType, graknGraph.getConcept(entityType.getId()));
    }

    @Test
    public void whenAttemptingToMutateViaTraversal_Throw(){
        expectedException.expect(VerificationException.class);
        expectedException.expectMessage("not read only");
        graknGraph.getTinkerTraversal().V().drop().iterate();
    }

    @Test
    public void whenGettingResourcesByValue_ReturnTheMatchingResources(){
        String targetValue = "Geralt";
        assertThat(graknGraph.getAttributesByValue(targetValue), is(empty()));

        AttributeType<String> t1 = graknGraph.putAttributeType("Parent 1", AttributeType.DataType.STRING);
        AttributeType<String> t2 = graknGraph.putAttributeType("Parent 2", AttributeType.DataType.STRING);

        Attribute<String> r1 = t1.putAttribute(targetValue);
        Attribute<String> r2 = t2.putAttribute(targetValue);
        t2.putAttribute("Dragon");

        assertThat(graknGraph.getAttributesByValue(targetValue), containsInAnyOrder(r1, r2));
    }

    @Test
    public void whenGettingTypesByName_ReturnTypes(){
        String entityTypeLabel = "My Entity Type";
        String relationTypeLabel = "My Relationship Type";
        String roleTypeLabel = "My Role Type";
        String resourceTypeLabel = "My Attribute Type";
        String ruleTypeLabel = "My Rule Type";

        assertNull(graknGraph.getEntityType(entityTypeLabel));
        assertNull(graknGraph.getRelationshipType(relationTypeLabel));
        assertNull(graknGraph.getRole(roleTypeLabel));
        assertNull(graknGraph.getAttributeType(resourceTypeLabel));
        assertNull(graknGraph.getRuleType(ruleTypeLabel));

        EntityType entityType = graknGraph.putEntityType(entityTypeLabel);
        RelationshipType relationshipType = graknGraph.putRelationshipType(relationTypeLabel);
        Role role = graknGraph.putRole(roleTypeLabel);
        AttributeType attributeType = graknGraph.putAttributeType(resourceTypeLabel, AttributeType.DataType.STRING);
        RuleType ruleType = graknGraph.putRuleType(ruleTypeLabel);

        assertEquals(entityType, graknGraph.getEntityType(entityTypeLabel));
        assertEquals(relationshipType, graknGraph.getRelationshipType(relationTypeLabel));
        assertEquals(role, graknGraph.getRole(roleTypeLabel));
        assertEquals(attributeType, graknGraph.getAttributeType(resourceTypeLabel));
        assertEquals(ruleType, graknGraph.getRuleType(ruleTypeLabel));
    }

    @Test
    public void whenGettingSubTypesFromRootMeta_IncludeAllTypes(){
        EntityType sampleEntityType = graknGraph.putEntityType("Sample Entity Type");
        RelationshipType sampleRelationshipType = graknGraph.putRelationshipType("Sample Relationship Type");

        assertThat(graknGraph.admin().getMetaConcept().subs().collect(Collectors.toSet()), containsInAnyOrder(
                graknGraph.admin().getMetaConcept(),
                graknGraph.admin().getMetaRelationType(),
                graknGraph.admin().getMetaEntityType(),
                graknGraph.admin().getMetaRuleType(),
                graknGraph.admin().getMetaResourceType(),
                graknGraph.admin().getMetaRuleConstraint(),
                graknGraph.admin().getMetaRuleInference(),
                sampleEntityType,
                sampleRelationshipType
        ));
    }

    @Test
    public void whenClosingReadOnlyGraph_EnsureTypesAreCached(){
        assertCacheOnlyContainsMetaTypes();
        //noinspection ResultOfMethodCallIgnored
        graknGraph.getMetaConcept().subs(); //This loads some types into transaction cache
        graknGraph.abort();
        assertCacheOnlyContainsMetaTypes(); //Ensure central cache is empty

        graknGraph = (GraknTxAbstract<?>) Grakn.session(Grakn.IN_MEMORY, graknGraph.getKeyspace()).open(GraknTxType.READ);

        Set<SchemaConcept> finalTypes = new HashSet<>();
        finalTypes.addAll(graknGraph.getMetaConcept().subs().collect(Collectors.toSet()));
        finalTypes.add(graknGraph.admin().getMetaRole());

        graknGraph.abort();

        for (SchemaConcept type : graknGraph.getGraphCache().getCachedTypes().values()) {
            assertTrue("Type [" + type + "] is missing from central cache after closing read only graph", finalTypes.contains(type));
        }
    }
    private void assertCacheOnlyContainsMetaTypes(){
        Set<Label> metas = Stream.of(Schema.MetaSchema.values()).map(Schema.MetaSchema::getLabel).collect(Collectors.toSet());
        graknGraph.getGraphCache().getCachedTypes().keySet().forEach(cachedLabel -> assertTrue("Type [" + cachedLabel + "] is missing from central cache", metas.contains(cachedLabel)));
    }

    @Test
    public void whenBuildingAConceptFromAVertex_ReturnConcept(){
        EntityTypeImpl et = (EntityTypeImpl) graknGraph.putEntityType("Sample Entity Type");
        assertEquals(et, graknGraph.factory().buildConcept(et.vertex()));
    }

    @Test
    public void whenPassingGraphToAnotherThreadWithoutOpening_Throw() throws ExecutionException, InterruptedException {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        GraknTx graph = Grakn.session(Grakn.IN_MEMORY, "testing").open(GraknTxType.WRITE);

        expectedException.expectCause(IsInstanceOf.instanceOf(GraphOperationException.class));
        expectedException.expectMessage(GraphOperationException.transactionClosed(graph, null).getMessage());

        Future future = pool.submit(() -> {
            graph.putEntityType("A Thing");
        });
        future.get();
    }

    @Test
    public void attemptingToUseClosedGraphFailingThenOpeningGraph_EnsureGraphIsUsable() throws InvalidGraphException {
        GraknTx graph = Grakn.session(Grakn.IN_MEMORY, "testing-again").open(GraknTxType.WRITE);
        graph.close();

        boolean errorThrown = false;
        try{
            graph.putEntityType("A Thing");
        } catch (GraphOperationException e){
            if(e.getMessage().equals(ErrorMessage.GRAPH_CLOSED_ON_ACTION.getMessage("closed", graph.getKeyspace()))){
                errorThrown = true;
            }
        }
        assertTrue("Graph not correctly closed", errorThrown);

        graph = Grakn.session(Grakn.IN_MEMORY, "testing-again").open(GraknTxType.WRITE);
        graph.putEntityType("A Thing");
    }

    @Test
    public void checkThatMainCentralCacheIsNotAffectedByTransactionModifications() throws InvalidGraphException, ExecutionException, InterruptedException {
        //Check Central cache is empty
        assertCacheOnlyContainsMetaTypes();

        Role r1 = graknGraph.putRole("r1");
        Role r2 = graknGraph.putRole("r2");
        EntityType e1 = graknGraph.putEntityType("e1").plays(r1).plays(r2);
        RelationshipType rel1 = graknGraph.putRelationshipType("rel1").relates(r1).relates(r2);

        //Purge the above concepts into the main cache
        graknGraph.commit();
        graknGraph = (GraknTxAbstract<?>) Grakn.session(Grakn.IN_MEMORY, graknGraph.getKeyspace()).open(GraknTxType.WRITE);

        //Check cache is in good order
        Collection<SchemaConcept> cachedValues = graknGraph.getGraphCache().getCachedTypes().values();
        assertTrue("Type [" + r1 + "] was not cached", cachedValues.contains(r1));
        assertTrue("Type [" + r2 + "] was not cached", cachedValues.contains(r2));
        assertTrue("Type [" + e1 + "] was not cached", cachedValues.contains(e1));
        assertTrue("Type [" + rel1 + "] was not cached", cachedValues.contains(rel1));

        assertThat(e1.plays().collect(Collectors.toSet()), containsInAnyOrder(r1, r2));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        //Mutate Schema in a separate thread
        pool.submit(() -> {
            GraknTx innerGraph = Grakn.session(Grakn.IN_MEMORY, graknGraph.getKeyspace()).open(GraknTxType.WRITE);
            EntityType entityType = innerGraph.getEntityType("e1");
            Role role = innerGraph.getRole("r1");
            entityType.deletePlays(role);
        }).get();

        //Check the above mutation did not affect central repo
        SchemaConcept foundE1 = graknGraph.getGraphCache().getCachedTypes().get(e1.getLabel());
        assertTrue("Main cache was affected by transaction", foundE1.asType().plays().anyMatch(role -> role.equals(r1)));
    }

    @Test
    public void whenClosingAGraphWhichWasJustCommitted_DoNothing(){
        graknGraph.commit();
        assertTrue("Graph is still open after commit", graknGraph.isClosed());
        graknGraph.close();
        assertTrue("Graph is somehow open after close", graknGraph.isClosed());
    }

    @Test
    public void whenCommittingAGraphWhichWasJustCommitted_DoNothing(){
        graknGraph.commit();
        assertTrue("Graph is still open after commit", graknGraph.isClosed());
        graknGraph.commit();
        assertTrue("Graph is somehow open after 2nd commit", graknGraph.isClosed());
    }

    @Test
    public void whenAttemptingToMutateReadOnlyGraph_Throw(){
        String keyspace = "my-read-only-graph";
        String entityType = "My Entity Type";
        String roleType1 = "My Role Type 1";
        String roleType2 = "My Role Type 2";
        String relationType1 = "My Relationship Type 1";
        String relationType2 = "My Relationship Type 2";
        String resourceType = "My Attribute Type";

        //Fail Some Mutations
        graknGraph = (GraknTxAbstract<?>) Grakn.session(Grakn.IN_MEMORY, keyspace).open(GraknTxType.READ);
        failMutation(graknGraph, () -> graknGraph.putEntityType(entityType));
        failMutation(graknGraph, () -> graknGraph.putRole(roleType1));
        failMutation(graknGraph, () -> graknGraph.putRelationshipType(relationType1));

        //Pass some mutations
        graknGraph.close();
        graknGraph = (GraknTxAbstract<?>) Grakn.session(Grakn.IN_MEMORY, keyspace).open(GraknTxType.WRITE);
        EntityType entityT = graknGraph.putEntityType(entityType);
        entityT.addEntity();
        Role roleT1 = graknGraph.putRole(roleType1);
        Role roleT2 = graknGraph.putRole(roleType2);
        RelationshipType relationT1 = graknGraph.putRelationshipType(relationType1).relates(roleT1);
        RelationshipType relationT2 = graknGraph.putRelationshipType(relationType2).relates(roleT2);
        AttributeType<String> resourceT = graknGraph.putAttributeType(resourceType, AttributeType.DataType.STRING);
        graknGraph.commit();

        //Fail some mutations again
        graknGraph = (GraknTxAbstract<?>) Grakn.session(Grakn.IN_MEMORY, keyspace).open(GraknTxType.READ);
        failMutation(graknGraph, entityT::addEntity);
        failMutation(graknGraph, () -> resourceT.putAttribute("A resource"));
        failMutation(graknGraph, () -> graknGraph.putEntityType(entityType));
        failMutation(graknGraph, () -> entityT.plays(roleT1));
        failMutation(graknGraph, () -> relationT1.relates(roleT2));
        failMutation(graknGraph, () -> relationT2.relates(roleT1));
    }
    private void failMutation(GraknTx graph, Runnable mutator){
        int vertexCount = graph.admin().getTinkerTraversal().V().toList().size();
        int eddgeCount = graph.admin().getTinkerTraversal().E().toList().size();

        Exception caughtException = null;
        try{
            mutator.run();
        } catch (Exception e){
            caughtException = e;
        }

        assertNotNull("No exception thrown when attempting to mutate a read only graph", caughtException);
        assertThat(caughtException, instanceOf(GraphOperationException.class));
        assertEquals(caughtException.getMessage(), ErrorMessage.TRANSACTION_READ_ONLY.getMessage(graph.getKeyspace()));
        assertEquals("A concept was added/removed using a read only graph", vertexCount, graph.admin().getTinkerTraversal().V().toList().size());
        assertEquals("An edge was added/removed using a read only graph", eddgeCount, graph.admin().getTinkerTraversal().E().toList().size());
    }

    @Test
    public void whenOpeningDifferentTypesOfGraphsOnTheSameThread_Throw(){
        String keyspace = "akeyspacewithkeys";
        GraknSession session = Grakn.session(Grakn.IN_MEMORY, keyspace);

        GraknTx graph = session.open(GraknTxType.READ);
        failAtOpeningGraph(session, GraknTxType.WRITE, keyspace);
        failAtOpeningGraph(session, GraknTxType.BATCH, keyspace);
        graph.close();

        //noinspection ResultOfMethodCallIgnored
        session.open(GraknTxType.BATCH);
        failAtOpeningGraph(session, GraknTxType.WRITE, keyspace);
        failAtOpeningGraph(session, GraknTxType.READ, keyspace);
    }

    private void failAtOpeningGraph(GraknSession session, GraknTxType txType, String keyspace){
        Exception exception = null;
        try{
            //noinspection ResultOfMethodCallIgnored
            session.open(txType);
        } catch (GraphOperationException e){
            exception = e;
        }
        assertNotNull(exception);
        assertThat(exception, instanceOf(GraphOperationException.class));
        assertEquals(exception.getMessage(), ErrorMessage.TRANSACTION_ALREADY_OPEN.getMessage(keyspace));
    }

    @Test
    public void whenShardingSuperNode_EnsureNewInstancesGoToNewShard(){
        EntityTypeImpl entityType = (EntityTypeImpl) graknGraph.putEntityType("The Special Type");
        Shard s1 = entityType.currentShard();

        //Add 3 instances to first shard
        Entity s1_e1 = entityType.addEntity();
        Entity s1_e2 = entityType.addEntity();
        Entity s1_e3 = entityType.addEntity();
        graknGraph.admin().shard(entityType.getId());

        Shard s2 = entityType.currentShard();

        //Add 5 instances to second shard
        Entity s2_e1 = entityType.addEntity();
        Entity s2_e2 = entityType.addEntity();
        Entity s2_e3 = entityType.addEntity();
        Entity s2_e4 = entityType.addEntity();
        Entity s2_e5 = entityType.addEntity();

        graknGraph.admin().shard(entityType.getId());
        Shard s3 = entityType.currentShard();

        //Add 2 instances to 3rd shard
        Entity s3_e1 = entityType.addEntity();
        Entity s3_e2 = entityType.addEntity();

        //Check Type was sharded correctly
        assertThat(entityType.shards().collect(Collectors.toSet()), containsInAnyOrder(s1, s2, s3));

        //Check shards have correct instances
        assertThat(s1.links().collect(Collectors.toSet()), containsInAnyOrder(s1_e1, s1_e2, s1_e3));
        assertThat(s2.links().collect(Collectors.toSet()), containsInAnyOrder(s2_e1, s2_e2, s2_e3, s2_e4, s2_e5));
        assertThat(s3.links().collect(Collectors.toSet()), containsInAnyOrder(s3_e1, s3_e2));
    }

    @Test
    public void whenCreatingAValidSchemaInSeparateThreads_EnsureValidationRulesHold() throws ExecutionException, InterruptedException {
        GraknSession session = Grakn.session(Grakn.IN_MEMORY, "hi");

        ExecutorService executor = Executors.newCachedThreadPool();

        executor.submit(() -> {
            //Resources
            try (GraknTx graph = session.open(GraknTxType.WRITE)) {
                AttributeType<Long> int_ = graph.putAttributeType("int", AttributeType.DataType.LONG);
                AttributeType<Long> foo = graph.putAttributeType("foo", AttributeType.DataType.LONG).sup(int_);
                graph.putAttributeType("bar", AttributeType.DataType.LONG).sup(int_);
                graph.putEntityType("FOO").attribute(foo);

                graph.commit();
            }
        }).get();

        //Relationship Which Has Resources
        try (GraknTx graph = session.open(GraknTxType.WRITE)) {
            graph.putEntityType("BAR").attribute(graph.getAttributeType("bar"));
            graph.commit();
        }
    }
}

/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.server;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.palantir.atlasdb.api.AtlasDbService;
import com.palantir.atlasdb.api.RangeToken;
import com.palantir.atlasdb.api.TableCell;
import com.palantir.atlasdb.api.TableCellVal;
import com.palantir.atlasdb.api.TableRange;
import com.palantir.atlasdb.api.TableRowResult;
import com.palantir.atlasdb.api.TableRowSelection;
import com.palantir.atlasdb.api.TransactionToken;
import com.palantir.atlasdb.impl.AtlasDbServiceImpl;
import com.palantir.atlasdb.impl.TableMetadataCache;
import com.palantir.atlasdb.jackson.AtlasJacksonModule;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.ColumnSelection;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.memory.InMemoryAtlasDbFactory;
import com.palantir.atlasdb.schema.AtlasSchema;
import com.palantir.atlasdb.schema.SweepSchema;
import com.palantir.atlasdb.schema.generated.SweepPriorityTable;
import com.palantir.atlasdb.schema.generated.SweepPriorityTable.CellsExamined;
import com.palantir.atlasdb.schema.generated.SweepPriorityTable.SweepPriorityRow;
import com.palantir.atlasdb.schema.generated.SweepPriorityTable.SweepPriorityRowResult;
import com.palantir.atlasdb.table.description.TableMetadata;
import com.palantir.atlasdb.transaction.impl.SerializableTransactionManager;

import feign.Feign;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import feign.jaxrs.JAXRSContract;
import io.dropwizard.Configuration;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.junit.DropwizardClientRule;

public class TransactionRemotingTest {
    public final static AtlasSchema schema = SweepSchema.INSTANCE;
    public final SerializableTransactionManager txMgr = InMemoryAtlasDbFactory.createInMemoryTransactionManager(schema);
    public final KeyValueService kvs = txMgr.getKeyValueService();
    public final TableMetadataCache cache = new TableMetadataCache(kvs);
    public final ObjectMapper mapper = new ObjectMapper(); { mapper.registerModule(new AtlasJacksonModule(cache).createModule()); }
    public final @Rule DropwizardClientRule dropwizard = new DropwizardClientRule(new AtlasDbServiceImpl(kvs, txMgr, cache));
    public AtlasDbService service;

    @SuppressWarnings("unchecked")
    @Before
    public void setupHacks() throws Exception {
        Field field = dropwizard.getClass().getDeclaredField("testSupport");
        field.setAccessible(true);
        DropwizardTestSupport<Configuration> testSupport = (DropwizardTestSupport<Configuration>) field.get(dropwizard);
        ObjectMapper mapper = testSupport.getEnvironment().getObjectMapper();
        mapper.registerModule(new AtlasJacksonModule(cache).createModule());
        mapper.registerModule(new GuavaModule());
    }

    @Before
    public void setup() {
        String uri = dropwizard.baseUri().toString();
        service = Feign.builder()
                .decoder(new JacksonDecoder(mapper))
                .encoder(new JacksonEncoder(mapper))
                .contract(new JAXRSContract())
                .target(AtlasDbService.class, uri);
    }

    @Test
    public void testGetAllTableNames() {
        Set<String> allTableNames = service.getAllTableNames();
        Collection<String> expectedTableNames =
                schema.getLatestSchema().getAllTablesAndIndexMetadata().keySet()
                        .stream()
                        .map(TableReference::getQualifiedName)
                        .collect(Collectors.toSet());
        Assert.assertTrue(allTableNames.containsAll(expectedTableNames));
    }

    @Test
    public void testGetTableMetadata() {
        TableMetadata metadata = service.getTableMetadata("sweep.priority");
        Assert.assertFalse(metadata.getColumns().hasDynamicColumns());
    }

    @Test
    public void testGetRowsNone() {
        setupFooStatus1("sweep.priority");
        TransactionToken txId = TransactionToken.autoCommit();
        TableRowResult badResults = service.getRows(txId, new TableRowSelection(
                "sweep.priority",
                ImmutableList.of(new byte[1]),
                ColumnSelection.all()));
        Assert.assertTrue(Iterables.isEmpty(badResults.getResults()));
    }

    @Test
    public void testGetRowsSome() {
        setupFooStatus1("sweep.priority");
        TransactionToken txId = TransactionToken.autoCommit();
        TableRowResult goodResults = service.getRows(txId, new TableRowSelection(
                "sweep.priority",
                ImmutableList.of(SweepPriorityRow.of("foo").persistToBytes()),
                ColumnSelection.all()));
        SweepPriorityRowResult result = SweepPriorityRowResult.of(Iterables.getOnlyElement(goodResults.getResults()));
        Assert.assertEquals(1L, result.getCellsExamined().longValue());
    }

    @Test
    public void testGetCellsNone() {
        setupFooStatus1("sweep.priority");
        TransactionToken txId = service.startTransaction();
        TableCellVal badCells = service.getCells(txId, new TableCell(
                "sweep.priority",
                ImmutableList.of(Cell.create(new byte[1], SweepPriorityTable.SweepPriorityNamedColumn.CELLS_EXAMINED.getShortName()))));
        Assert.assertTrue(badCells.getResults().isEmpty());
    }

    @Test
    public void testGetCellsSome() {
        setupFooStatus1("sweep.priority");
        TransactionToken txId = service.startTransaction();
        Map<Cell, byte[]> contents = getSweepPriorityTableContents();
        TableCellVal goodCells = service.getCells(txId, new TableCell(
                "sweep.priority",
                contents.keySet()));
        Assert.assertEquals(contents.keySet(), goodCells.getResults().keySet());
        Assert.assertArrayEquals(Iterables.getOnlyElement(contents.values()), Iterables.getOnlyElement(goodCells.getResults().values()));
        service.commit(txId);
    }

    @Test
    public void testGetRangeNone() {
        setupFooStatus1("sweep.priority");
        TransactionToken token = TransactionToken.autoCommit();
        RangeToken range = service.getRange(token, new TableRange(
                "sweep.priority",
                new byte[1],
                new byte[2],
                ImmutableList.<byte[]>of(),
                10));
        Assert.assertTrue(Iterables.isEmpty(range.getResults().getResults()));
        Assert.assertNull(range.getNextRange());
    }

    @Test
    public void testGetRangeSome() {
        setupFooStatus1("sweep.priority");
        TransactionToken token = TransactionToken.autoCommit();
        RangeToken range = service.getRange(token, new TableRange(
                "sweep.priority",
                new byte[0],
                new byte[0],
                ImmutableList.<byte[]>of(),
                10));
        SweepPriorityRowResult result = SweepPriorityRowResult.of(Iterables.getOnlyElement(range.getResults().getResults()));
        Assert.assertEquals(1L, result.getCellsExamined().longValue());
        Assert.assertNull(range.getNextRange());
    }

    @Test
    public void testDelete() {
        setupFooStatus1("sweep.priority");
        Map<Cell, byte[]> contents = getSweepPriorityTableContents();
        TransactionToken token = TransactionToken.autoCommit();
        service.delete(token, new TableCell(
                "sweep.priority",
                contents.keySet()));
        RangeToken range = service.getRange(token, new TableRange(
                "sweep.priority",
                new byte[0],
                new byte[0],
                ImmutableList.<byte[]>of(),
                10));
        Assert.assertTrue(Iterables.isEmpty(range.getResults().getResults()));
        Assert.assertNull(range.getNextRange());
    }

    @Test
    public void testAbort() {
        TransactionToken txId = service.startTransaction();
        service.put(txId, new TableCellVal("sweep.priority", getSweepPriorityTableContents()));
        service.abort(txId);
        service.commit(txId);
        txId = TransactionToken.autoCommit();
        RangeToken range = service.getRange(txId, new TableRange(
                "sweep.priority",
                new byte[0],
                new byte[0],
                ImmutableList.<byte[]>of(),
                10));
        Assert.assertTrue(Iterables.isEmpty(range.getResults().getResults()));
        Assert.assertNull(range.getNextRange());
    }

    @Test
    public void testRaw() throws JsonProcessingException {
        String tableName = "ns.my_table";
        service.createTable(tableName);
        TransactionToken txId = service.startTransaction();
        TableCellVal putArg = new TableCellVal(tableName, getSweepPriorityTableContents());
        String str = mapper.writeValueAsString(putArg);
        System.out.println(str);
        service.put(txId, putArg);
    }

    @Test
    public void testRaw2() throws JsonProcessingException {
        String tableName = "ns.my_table";
        service.createTable(tableName);
        TransactionToken txId = service.startTransaction();
        Cell rawCell = Cell.create(new byte[] {0, 1, 2}, new byte[] {3, 4, 5});
        TableCellVal putArg = new TableCellVal(tableName, ImmutableMap.of(rawCell, new byte[] {40, 0}));
        service.put(txId, putArg);

        TableCell tableCell = new TableCell(tableName, ImmutableList.of(rawCell));

        service.getCells(txId, tableCell);
    }

    private void setupFooStatus1(String table) {
        TransactionToken txId = service.startTransaction();
        service.put(txId, new TableCellVal(table, getSweepPriorityTableContents()));
        service.commit(txId);
    }

    private Map<Cell, byte[]> getSweepPriorityTableContents() {
        byte[] row = SweepPriorityRow.of("foo").persistToBytes();
        CellsExamined status = CellsExamined.of(1L);
        Cell cell = Cell.create(row, status.persistColumnName());
        return ImmutableMap.of(cell, status.persistValue());
    }
}

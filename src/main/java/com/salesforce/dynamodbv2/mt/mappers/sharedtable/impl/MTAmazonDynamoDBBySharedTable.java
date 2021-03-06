/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dynamodbv2.mt.mappers.sharedtable.impl;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.CreateTableResult;
import com.amazonaws.services.dynamodbv2.model.DeleteItemRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteItemResult;
import com.amazonaws.services.dynamodbv2.model.DeleteTableRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteTableResult;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableResult;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.amazonaws.services.dynamodbv2.model.Record;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.StreamRecord;
import com.amazonaws.services.dynamodbv2.model.StreamSpecification;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import com.amazonaws.services.dynamodbv2.model.UpdateItemResult;
import com.amazonaws.services.dynamodbv2.streamsadapter.model.RecordAdapter;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessor;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.types.InitializationInput;
import com.amazonaws.services.kinesis.clientlibrary.types.ProcessRecordsInput;
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownInput;
import com.google.common.cache.Cache;
import com.salesforce.dynamodbv2.mt.cache.MTCache;
import com.salesforce.dynamodbv2.mt.context.MTAmazonDynamoDBContextProvider;
import com.salesforce.dynamodbv2.mt.mappers.MTAmazonDynamoDBBase;
import com.salesforce.dynamodbv2.mt.mappers.metadata.DynamoTableDescription;
import com.salesforce.dynamodbv2.mt.mappers.metadata.DynamoTableDescriptionImpl;
import com.salesforce.dynamodbv2.mt.mappers.sharedtable.impl.FieldPrefixFunction.FieldValue;
import com.salesforce.dynamodbv2.mt.repo.MTTableDescriptionRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * Allows a developer using the mt-dynamo library to provide a custom mapping between tables that clients interact with
 * and the physical tables where the data for those tables are stored.  It support mapping many virtual tables to a
 * single physical table, mapping field names and types, secondary indexes.  It supports for allowing multi-tenant
 * context to be added to table and index hash key fields.  Throughout this documentation, virtual tables are meant to
 * represent tables as they are understood by the developer using the DynamoDB Java API.  Physical tables represent the
 * tables that store the data in AWS.
 *
 * SharedTableCustomDynamicBuilder provides a series of static methods that providing builders that are
 * preconfigured to support a number of common mappings.  See Javadoc for each provided builder for details.
 *
 * Supported methods: create|describe|delete* Table, get|put|update Item, query**, scan**
 *
 * * See deleteTableAsync and truncateOnDeleteTable in the SharedTableCustomDynamicBuilder for details on how to
 * control behavior that is specific to deleteTable.
 * ** Only EQ conditions are supported.
 *
 * Deleting and recreating tables without deleting all table data(see truncateOnDeleteTable) may yield unexpected results.
 *
 * @author msgroi
 */
public class MTAmazonDynamoDBBySharedTable extends MTAmazonDynamoDBBase {

    private static final Logger log = LoggerFactory.getLogger(MTAmazonDynamoDBBySharedTable.class);

    private final String name;

    private final MTTableDescriptionRepo mtTableDescriptionRepo;
    private final Cache<String, TableMapping> tableMappingCache;
    private final TableMappingFactory tableMappingFactory;
    private final boolean deleteTableAsync;
    private final boolean truncateOnDeleteTable;

    @SuppressWarnings("unchecked")
    public MTAmazonDynamoDBBySharedTable(String name,
                                         MTAmazonDynamoDBContextProvider mtContext,
                                         AmazonDynamoDB amazonDynamoDB,
                                         TableMappingFactory tableMappingFactory,
                                         MTTableDescriptionRepo mtTableDescriptionRepo,
                                         boolean deleteTableAsync,
                                         boolean truncateOnDeleteTable) {
        super(mtContext, amazonDynamoDB);
        this.name = name;
        this.mtTableDescriptionRepo = mtTableDescriptionRepo;
        tableMappingCache = new MTCache(mtContext);
        this.tableMappingFactory = tableMappingFactory;
        this.deleteTableAsync = deleteTableAsync;
        this.truncateOnDeleteTable = truncateOnDeleteTable;
    }

    public CreateTableResult createTable(CreateTableRequest createTableRequest) {
        return new CreateTableResult().withTableDescription(mtTableDescriptionRepo.createTable(createTableRequest));
    }

    public DeleteItemResult deleteItem(DeleteItemRequest deleteItemRequest) {
        // map table name
        deleteItemRequest = deleteItemRequest.clone();
        TableMapping tableMapping = getTableMapping(deleteItemRequest.getTableName());
        deleteItemRequest.withTableName(tableMapping.getPhysicalTable().getTableName());

        // map key
        deleteItemRequest.setKey(tableMapping.getItemMapper().apply(deleteItemRequest.getKey()));

        // delete
        return getAmazonDynamoDB().deleteItem(deleteItemRequest);
    }

    @SuppressWarnings("Duplicates")
    public DeleteTableResult deleteTable(DeleteTableRequest deleteTableRequest) {
        if (deleteTableAsync) {
            Executors.newSingleThreadExecutor().submit(() -> {
                deleteTableInternal(deleteTableRequest);
            });
            return new DeleteTableResult().withTableDescription(mtTableDescriptionRepo.getTableDescription(deleteTableRequest.getTableName()));
        } else {
            return deleteTableInternal(deleteTableRequest);
        }
    }

    public DescribeTableResult describeTable(DescribeTableRequest describeTableRequest) {
        return new DescribeTableResult().withTable(mtTableDescriptionRepo.getTableDescription(describeTableRequest.getTableName()).withTableStatus("ACTIVE"));
    }

    public GetItemResult getItem(GetItemRequest getItemRequest) {
        // map table name
        getItemRequest = getItemRequest.clone();
        TableMapping tableMapping = getTableMapping(getItemRequest.getTableName());
        getItemRequest.withTableName(tableMapping.getPhysicalTable().getTableName());

        // map key
        getItemRequest.setKey(tableMapping.getItemMapper().apply(getItemRequest.getKey()));

        // map result
        GetItemResult getItemResult = getAmazonDynamoDB().getItem(getItemRequest);
        if (getItemResult.getItem() != null) {
            getItemResult.withItem(tableMapping.getItemMapper().reverse(getItemResult.getItem()));
        }

        return getItemResult;
    }

    private TableMapping getTableMapping(String virtualTableName) {
        try {
            return tableMappingCache.get(virtualTableName, () ->
                    tableMappingFactory.getTableMapping(new DynamoTableDescriptionImpl(mtTableDescriptionRepo.getTableDescription(virtualTableName))));
        } catch (ExecutionException e) {
            throw new RuntimeException("exception mapping virtual table " + virtualTableName, e);
        }
    }

    public PutItemResult putItem(PutItemRequest putItemRequest) {
        // map table name
        putItemRequest = putItemRequest.clone();
        TableMapping tableMapping = getTableMapping(putItemRequest.getTableName());
        putItemRequest.withTableName(tableMapping.getPhysicalTable().getTableName());

        // map item
        putItemRequest.setItem(tableMapping.getItemMapper().apply(putItemRequest.getItem()));

        // put
        return getAmazonDynamoDB().putItem(putItemRequest);
    }

    public QueryResult query(QueryRequest queryRequest) {
        // map table name
        queryRequest = queryRequest.clone();
        TableMapping tableMapping = getTableMapping(queryRequest.getTableName());
        queryRequest.withTableName(tableMapping.getPhysicalTable().getTableName());

        // map query request
        tableMapping.getQueryMapper().apply(queryRequest);

        // map result
        QueryResult queryResult = getAmazonDynamoDB().query(queryRequest);
        queryResult.setItems(queryResult.getItems().stream().map(item -> tableMapping.getItemMapper().reverse(item)).collect(toList()));

        return queryResult;
    }

    public ScanResult scan(ScanRequest scanRequest) {
        // map table name
        ScanRequest clonedScanRequest = scanRequest.clone();
        TableMapping tableMapping = getTableMapping(clonedScanRequest.getTableName());
        clonedScanRequest.withTableName(tableMapping.getPhysicalTable().getTableName());

        // map query request
        clonedScanRequest.setExpressionAttributeNames(Optional.ofNullable(clonedScanRequest.getFilterExpression()).map(s -> new HashMap<>(clonedScanRequest.getExpressionAttributeNames())).orElseGet(HashMap::new));
        clonedScanRequest.setExpressionAttributeValues(Optional.ofNullable(clonedScanRequest.getFilterExpression()).map(s -> new HashMap<>(clonedScanRequest.getExpressionAttributeValues())).orElseGet(HashMap::new));
        tableMapping.getQueryMapper().apply(clonedScanRequest);

        // map result
        ScanResult scanResult = getAmazonDynamoDB().scan(clonedScanRequest);
        scanResult.setItems(scanResult.getItems().stream().map(item -> tableMapping.getItemMapper().reverse(item)).collect(toList()));

        return scanResult;
    }

    public UpdateItemResult updateItem(UpdateItemRequest updateItemRequest) {
        // map table name
        updateItemRequest = updateItemRequest.clone();
        TableMapping tableMapping = getTableMapping(updateItemRequest.getTableName());
        updateItemRequest.withTableName(tableMapping.getPhysicalTable().getTableName());

        // map key
        updateItemRequest.setKey(tableMapping.getItemMapper().apply(updateItemRequest.getKey()));

        // map attributeUpdates // TODO msgroi todo

        // map updateCondition // TODO msgroi todo

        // update
        return getAmazonDynamoDB().updateItem(updateItemRequest);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public List<MTStreamDescription> listStreams(IRecordProcessorFactory factory) {
        return tableMappingCache.asMap().values().stream()
                .map(TableMapping::getPhysicalTable).filter(physicalTable -> Optional.ofNullable(physicalTable.getStreamSpecification())
                .map(StreamSpecification::isStreamEnabled).orElse(false))
                .map(physicalTable -> new MTStreamDescription()
                        .withLabel(physicalTable.getTableName())
                        .withArn(physicalTable.getLastStreamArn())
                        .withRecordProcessorFactory(newAdapter(factory, physicalTable))).collect(toList());
    }

    private IRecordProcessorFactory newAdapter(IRecordProcessorFactory factory, DynamoTableDescription physicalTable) {
        return () -> new RecordProcessor(factory.createProcessor(), physicalTable);
    }

    private class RecordProcessor implements IRecordProcessor {
        private final IRecordProcessor processor;
        private final DynamoTableDescription physicalTable;

        RecordProcessor(IRecordProcessor processor, DynamoTableDescription physicalTable) {
            this.processor = processor;
            this.physicalTable = physicalTable;
        }

        @Override
        public void initialize(InitializationInput initializationInput) {
            processor.initialize(initializationInput);
        }

        @Override
        public void processRecords(ProcessRecordsInput processRecordsInput) {
            List<com.amazonaws.services.kinesis.model.Record> records = processRecordsInput.getRecords().stream()
                    .map(RecordAdapter.class::cast).map(this::toMTRecord).collect(toList());
            processor.processRecords(processRecordsInput.withRecords(records));
        }

        private com.amazonaws.services.kinesis.model.Record toMTRecord(RecordAdapter adapter) {
            Record r = adapter.getInternalObject();
            StreamRecord streamRecord = r.getDynamodb();
            FieldValue fieldValue = new FieldPrefixFunction(".").reverse(streamRecord.getKeys().get(physicalTable.getPrimaryKey().getHashKey()).getS());
            MTAmazonDynamoDBContextProvider mtContext = getMTContext();
            TableMapping tableMapping;
            try {
                mtContext.setContext(fieldValue.getMtContext());
                tableMapping = getTableMapping(fieldValue.getTableIndex()); // getting a table mapping requires tenant context
            } finally {
                mtContext.setContext(null);
            }
            ItemMapper itemMapper = tableMapping.getItemMapper();
            streamRecord.setKeys(itemMapper.reverse(streamRecord.getKeys()));
            streamRecord.setOldImage(itemMapper.reverse(streamRecord.getOldImage()));
            streamRecord.setNewImage(itemMapper.reverse(streamRecord.getNewImage()));
            return new RecordAdapter(new MTRecord()
                    .withAwsRegion(r.getAwsRegion())
                    .withDynamodb(streamRecord)
                    .withEventID(r.getEventID())
                    .withEventName(r.getEventName())
                    .withEventSource(r.getEventSource())
                    .withEventVersion(r.getEventVersion())
                    .withContext(fieldValue.getMtContext())
                    .withTableName(fieldValue.getTableIndex()));
        }

        @Override
        public void shutdown(ShutdownInput shutdownInput) {
            processor.shutdown(shutdownInput);
        }

    }

    private DeleteTableResult deleteTableInternal(DeleteTableRequest deleteTableRequest) {
        String tableDesc = "table=" + deleteTableRequest.getTableName() + " " + (deleteTableAsync ? "asynchronously" : "synchronously");
        log.warn("dropping " + tableDesc);
        truncateTable(deleteTableRequest.getTableName());
        DeleteTableResult deleteTableResult = new DeleteTableResult().withTableDescription(mtTableDescriptionRepo.deleteTable(deleteTableRequest.getTableName()));
        log.warn("dropped " + tableDesc);
        return deleteTableResult;
    }

    @SuppressWarnings("Duplicates")
    private void truncateTable(String tableName) {
        if (truncateOnDeleteTable) {
            ScanResult scanResult = scan(new ScanRequest().withTableName(tableName));
            log.warn("truncating " + scanResult.getItems().size() + " items from table=" + tableName);
            for (Map<String, AttributeValue> item : scanResult.getItems()) {
                deleteItem(new DeleteItemRequest().withTableName(tableName).withKey(getKeyFromItem(item, tableName)));
            }
            log.warn("truncation of " + scanResult.getItems().size() + " items from table=" + tableName + " complete");
        } else {
            log.info("truncateOnDeleteTable is disabled for " + tableName + ", skipping truncation");
        }
    }

    private Map<String, AttributeValue> getKeyFromItem(Map<String, AttributeValue> item, String tableName) {
        return describeTable(new DescribeTableRequest().withTableName(tableName)).getTable().getKeySchema().stream()
                .collect(Collectors.toMap(KeySchemaElement::getAttributeName,
                        keySchemaElement -> item.get(keySchemaElement.getAttributeName())));
    }

}
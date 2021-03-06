/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dynamodbv2.mt.mappers.sharedtable.impl;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.salesforce.dynamodbv2.mt.admin.AmazonDynamoDBAdminUtils;
import com.salesforce.dynamodbv2.mt.context.MTAmazonDynamoDBContextProvider;
import com.salesforce.dynamodbv2.mt.mappers.index.DynamoSecondaryIndexMapper;
import com.salesforce.dynamodbv2.mt.mappers.metadata.DynamoTableDescription;
import com.salesforce.dynamodbv2.mt.mappers.metadata.DynamoTableDescriptionImpl;
import com.salesforce.dynamodbv2.mt.mappers.sharedtable.CreateTableRequestFactory;

import java.util.Optional;

/**
 * Creates TableMapping objects that contain the state of given mapping of a virtual table to a physical table.  The
 * TableMapping also includes methods for retrieving the virtual and physical descriptions, and logic for mapping of fields
 * from virtual to physical and back.
 *
 * This class is also responsible for triggering the creation of the physical tables appropriately.
 *
 * @author msgroi
 */
public class TableMappingFactory {

    private final AmazonDynamoDBAdminUtils dynamoDBAdminUtils;
    private final CreateTableRequestFactory createTableRequestFactory;
    private final MTAmazonDynamoDBContextProvider mtContext;
    private final DynamoSecondaryIndexMapper secondaryIndexMapper;
    private final String delimiter;
    private final AmazonDynamoDB amazonDynamoDB;
    private final int pollIntervalSeconds;

    public TableMappingFactory(CreateTableRequestFactory createTableRequestFactory,
                               MTAmazonDynamoDBContextProvider mtContext,
                               DynamoSecondaryIndexMapper secondaryIndexMapper,
                               String delimiter,
                               AmazonDynamoDB amazonDynamoDB,
                               int pollIntervalSeconds) {
        this.createTableRequestFactory = createTableRequestFactory;
        this.secondaryIndexMapper = secondaryIndexMapper;
        this.mtContext = mtContext;
        this.delimiter = delimiter;
        this.amazonDynamoDB = amazonDynamoDB;
        this.dynamoDBAdminUtils = new AmazonDynamoDBAdminUtils(amazonDynamoDB);
        this.pollIntervalSeconds = pollIntervalSeconds;
        precreateTables(createTableRequestFactory);
    }

    private void precreateTables(CreateTableRequestFactory createTableRequestFactory) {
        createTableRequestFactory.precreateTables().forEach(this::createTableIfNotExists);
    }

    /*
     * Creates the table mapping, creates the table if it does not exist, sets the physical table description
     * back onto the table mapping so it includes things that can only be determined after the physical
     * table is created, like the streamArn.
     */
    TableMapping getTableMapping(DynamoTableDescription virtualTableDescription) {
        TableMapping tableMapping = new TableMapping(virtualTableDescription,
                                                     createTableRequestFactory,
                                                     secondaryIndexMapper,
                                                     mtContext,
                                                     delimiter);
        tableMapping.setPhysicalTable(createTableIfNotExists(tableMapping.getPhysicalTable().getCreateTableRequest()));
        return tableMapping;
    }

    private DynamoTableDescriptionImpl createTableIfNotExists(CreateTableRequest physicalTable) {
        // does not exist, create
        if (!getTableDescription(physicalTable.getTableName()).isPresent()) {
            dynamoDBAdminUtils.createTableIfNotExists(physicalTable, pollIntervalSeconds);
        }
        return new DynamoTableDescriptionImpl(amazonDynamoDB.describeTable(physicalTable.getTableName()).getTable());
    }

    private Optional<TableDescription> getTableDescription(String tableName) {
        try {
            return Optional.of(amazonDynamoDB.describeTable(tableName).getTable());
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        } catch (IllegalStateException e) {
            throw new RuntimeException("Mt context available.  When chaining, you must either set the mt context before " +
                                       "building, or set precreateTables=false");
        }
    }

}
/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dynamodbv2.mt.mappers;

import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndex;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.LocalSecondaryIndex;
import com.amazonaws.services.dynamodbv2.model.Projection;
import com.amazonaws.services.dynamodbv2.model.ProjectionType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.StreamSpecification;
import com.salesforce.dynamodbv2.mt.mappers.index.DynamoSecondaryIndex.DynamoSecondaryIndexType;
import com.salesforce.dynamodbv2.mt.mappers.metadata.PrimaryKey;

import java.util.ArrayList;
import java.util.List;

import static com.amazonaws.services.dynamodbv2.model.KeyType.HASH;
import static com.amazonaws.services.dynamodbv2.model.KeyType.RANGE;
import static com.salesforce.dynamodbv2.mt.mappers.index.DynamoSecondaryIndex.DynamoSecondaryIndexType.GSI;

/*
 * @author msgroi
 */
public class CreateTableRequestBuilder {

    private final CreateTableRequest createTableRequest = new CreateTableRequest();

    public static CreateTableRequestBuilder builder() {
        return new CreateTableRequestBuilder();
    }

    public CreateTableRequest build() {
        setDefaults();
        return createTableRequest;
    }

    private void setDefaults() {
        if (createTableRequest.getProvisionedThroughput() == null) {
            createTableRequest.setProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(1L).withWriteCapacityUnits(1L));
        }
    }

    public CreateTableRequestBuilder withTableName(String tableName) {
        this.createTableRequest.withTableName(tableName);
        return this;
    }

    public String getTableName() {
        return createTableRequest.getTableName();
    }

    public CreateTableRequestBuilder withTableKeySchema(String hashKeyField, ScalarAttributeType hashKeyType) {
        addAttributeDefinition(hashKeyField, hashKeyType);
        createTableRequest.withKeySchema(new KeySchemaElement(hashKeyField, HASH));
        return this;
    }

    public CreateTableRequestBuilder withTableKeySchema(String hashKeyField,
                                                 ScalarAttributeType hashKeyType,
                                                 String rangeKeyField,
                                                 ScalarAttributeType rangeKeyType) {
        addAttributeDefinition(hashKeyField, hashKeyType);
        addAttributeDefinition(rangeKeyField, rangeKeyType);
        createTableRequest.withKeySchema(new KeySchemaElement(hashKeyField, HASH),
                                         new KeySchemaElement(rangeKeyField, RANGE));
        return this;
    }

    @SuppressWarnings("all")
    public CreateTableRequestBuilder addSI(String indexName,
                                    DynamoSecondaryIndexType indexType,
                                    PrimaryKey secondaryIndexKey,
                                    Long provisionedThroughput) {
        if (indexType == GSI) {
            if (this.createTableRequest.getGlobalSecondaryIndexes() == null) {
                this.createTableRequest.setGlobalSecondaryIndexes(new ArrayList<>());
            }
            this.createTableRequest.getGlobalSecondaryIndexes().add(
                    new GlobalSecondaryIndex().withIndexName(indexName)
                            .withKeySchema(buildKeySchema(secondaryIndexKey))
                            .withProvisionedThroughput(new ProvisionedThroughput(provisionedThroughput, provisionedThroughput))
                            .withProjection(new Projection().withProjectionType(ProjectionType.ALL)));
        } else {
            if (this.createTableRequest.getLocalSecondaryIndexes() == null) {
                this.createTableRequest.setLocalSecondaryIndexes(new ArrayList<>());
            }
            this.createTableRequest.getLocalSecondaryIndexes().add(
                    new LocalSecondaryIndex().withIndexName(indexName)
                            .withKeySchema(buildKeySchema(secondaryIndexKey))
                            .withProjection(new Projection().withProjectionType(ProjectionType.ALL)));
        }
        return this;
    }

    public CreateTableRequestBuilder withProvisionedThroughput(Long readCapacityUnits, Long writeCapacityUnits) {
        this.createTableRequest.withProvisionedThroughput(new ProvisionedThroughput(readCapacityUnits, writeCapacityUnits));
        return this;
    }

    @SuppressWarnings("all")
    public CreateTableRequestBuilder withStreamSpecification(StreamSpecification streamSpecification) {
        this.createTableRequest.withStreamSpecification(streamSpecification);
        return this;
    }

    private void addAttributeDefinition(String field, ScalarAttributeType fieldType) {
        if (createTableRequest.getAttributeDefinitions() == null) {
            createTableRequest.setAttributeDefinitions(new ArrayList<>());
        }
        if (createTableRequest.getAttributeDefinitions().stream()
                .noneMatch(attributeDefinition -> field.equals(attributeDefinition.getAttributeName()))) {
            this.createTableRequest.getAttributeDefinitions().add(new AttributeDefinition(field, fieldType));
        }
    }

    private List<KeySchemaElement> buildKeySchema(PrimaryKey primaryKey) {
        List<KeySchemaElement> keySchemaElements = new ArrayList<>();
        keySchemaElements.add(new KeySchemaElement(primaryKey.getHashKey(), HASH));
        addAttributeDefinition(primaryKey.getHashKey(), primaryKey.getHashKeyType());
        if (primaryKey.getRangeKey().isPresent()) {
            keySchemaElements.add(new KeySchemaElement(primaryKey.getRangeKey().get(), RANGE));
            addAttributeDefinition(primaryKey.getRangeKey().get(), primaryKey.getRangeKeyType().get());
        }
        return keySchemaElements;
    }
}

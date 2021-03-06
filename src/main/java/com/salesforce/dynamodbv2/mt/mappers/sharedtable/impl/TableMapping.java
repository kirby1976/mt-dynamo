/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dynamodbv2.mt.mappers.sharedtable.impl;

import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.salesforce.dynamodbv2.mt.context.MTAmazonDynamoDBContextProvider;
import com.salesforce.dynamodbv2.mt.mappers.MappingException;
import com.salesforce.dynamodbv2.mt.mappers.index.DynamoSecondaryIndex;
import com.salesforce.dynamodbv2.mt.mappers.index.DynamoSecondaryIndexMapper;
import com.salesforce.dynamodbv2.mt.mappers.metadata.DynamoTableDescription;
import com.salesforce.dynamodbv2.mt.mappers.metadata.DynamoTableDescriptionImpl;
import com.salesforce.dynamodbv2.mt.mappers.metadata.PrimaryKey;
import com.salesforce.dynamodbv2.mt.mappers.sharedtable.CreateTableRequestFactory;
import com.salesforce.dynamodbv2.mt.mappers.sharedtable.impl.FieldMapping.Field;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.amazonaws.services.dynamodbv2.model.ScalarAttributeType.S;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.salesforce.dynamodbv2.mt.mappers.index.DynamoSecondaryIndex.DynamoSecondaryIndexType.LSI;
import static com.salesforce.dynamodbv2.mt.mappers.sharedtable.impl.FieldMapping.IndexType.SECONDARYINDEX;
import static com.salesforce.dynamodbv2.mt.mappers.sharedtable.impl.FieldMapping.IndexType.TABLE;
import static java.util.Optional.ofNullable;

/*
 * Holds the state of mapping of a virtual table to a physical table.  It provides methods for retrieving the virtual
 * and physical descriptions, the mapping of fields from virtual to physical and back.
 *
 * @author msgroi
 */
class TableMapping {

    private final DynamoTableDescription virtualTable;
    private DynamoTableDescription physicalTable;
    private final DynamoSecondaryIndexMapper secondaryIndexMapper;
    private final Map<String, List<FieldMapping>> virtualToPhysicalMappings;
    private final Map<String, List<FieldMapping>> physicalToVirtualMappings;
    private final Map<DynamoSecondaryIndex, List<FieldMapping>> secondaryIndexFieldMappings;

    private final ItemMapper itemMapper;
    private final QueryMapper queryMapper;

    TableMapping(DynamoTableDescription virtualTable,
                 CreateTableRequestFactory createTableRequestFactory,
                 DynamoSecondaryIndexMapper secondaryIndexMapper,
                 MTAmazonDynamoDBContextProvider mtContext,
                 String delimiter) {
        physicalTable = lookupPhysicalTable(virtualTable, createTableRequestFactory);
        validatePhysicalTable(physicalTable);
        this.secondaryIndexMapper = secondaryIndexMapper;
        this.virtualTable = virtualTable;
        this.secondaryIndexFieldMappings = buildIndexPrimaryKeyFieldMappings(virtualTable, physicalTable, secondaryIndexMapper);
        this.virtualToPhysicalMappings = buildAllVirtualToPhysicalFieldMappings(virtualTable);
        this.physicalToVirtualMappings = buildAllPhysicalToVirtualFieldMappings(virtualToPhysicalMappings);
        validateVirtualPhysicalCompatibility();
        FieldMapper fieldMapper = new FieldMapper(mtContext,
                                                  virtualTable.getTableName(),
                                                  new FieldPrefixFunction(delimiter));
        itemMapper = new ItemMapper(this, fieldMapper);
        queryMapper = new QueryMapper(this, fieldMapper);
    }

    DynamoTableDescription getVirtualTable() {
        return virtualTable;
    }

    DynamoTableDescription getPhysicalTable() {
        return physicalTable;
    }

    ItemMapper getItemMapper() {
        return itemMapper;
    }

    QueryMapper getQueryMapper() {
        return queryMapper;
    }

    /*
     * Returns a mapping of virtual to physical fields.
     */
    Map<String, List<FieldMapping>> getAllVirtualToPhysicalFieldMappings() {
        return virtualToPhysicalMappings;
    }

    /*
     * Returns a mapping of physical to virtual fields.
     */
    Map<String, List<FieldMapping>> getAllPhysicalToVirtualFieldMappings() {
        return physicalToVirtualMappings;
    }

    /*
     * Returns a mapping of primary key fields for a specific secondary index, virtual to physical.
     */
    List<FieldMapping> getIndexPrimaryKeyFieldMappings(DynamoSecondaryIndex virtualSecondaryIndex) {
        return secondaryIndexFieldMappings.get(virtualSecondaryIndex);
    }

    /*
     * Returns a mapping of table-level primary key fields only, virtual to physical.
     */
    private List<FieldMapping> getTablePrimaryKeyFieldMappings() {
        List<FieldMapping> fieldMappings = new ArrayList<>();
        fieldMappings.add(new FieldMapping(new Field(virtualTable.getPrimaryKey().getHashKey(),
                virtualTable.getPrimaryKey().getHashKeyType()),
                new Field(physicalTable.getPrimaryKey().getHashKey(),
                        physicalTable.getPrimaryKey().getHashKeyType()),
                virtualTable.getTableName(),
                physicalTable.getTableName(),
                TABLE,
                true));
        if (virtualTable.getPrimaryKey().getRangeKey().isPresent()) {
            fieldMappings.add(new FieldMapping(new Field(virtualTable.getPrimaryKey().getRangeKey().get(),
                    virtualTable.getPrimaryKey().getRangeKeyType().get()),
                    new Field(physicalTable.getPrimaryKey().getRangeKey().get(),
                            physicalTable.getPrimaryKey().getRangeKeyType().get()),
                    virtualTable.getTableName(),
                    physicalTable.getTableName(),
                    TABLE,
                    false));
        }
        return fieldMappings;
    }

    /*
     * Calls the provided CreateTableRequestFactory passing in the virtual table description and returns the corresponding
     * physical table.  Throws a ResourceNotFoundException if the implementation returns null.
     */
    private DynamoTableDescription lookupPhysicalTable(DynamoTableDescription virtualTable,
                                                       CreateTableRequestFactory createTableRequestFactory) {
        return new DynamoTableDescriptionImpl(ofNullable(
                createTableRequestFactory.getCreateTableRequest(virtualTable))
                .orElseThrow((Supplier<ResourceNotFoundException>) () ->
                        new ResourceNotFoundException("table " + virtualTable.getTableName() + " is not a supported table")));
    }

    private Map<String, List<FieldMapping>> buildAllVirtualToPhysicalFieldMappings(DynamoTableDescription virtualTable) {
        Map<String, List<FieldMapping>> fieldMappings = new HashMap<>();
        getTablePrimaryKeyFieldMappings().forEach(fieldMapping -> addFieldMapping(fieldMappings, fieldMapping));
        virtualTable.getSIs().forEach(virtualSI -> getIndexPrimaryKeyFieldMappings(virtualSI).forEach(fieldMapping -> addFieldMapping(fieldMappings, fieldMapping)));
        return fieldMappings;
    }

    private Map<String, List<FieldMapping>> buildAllPhysicalToVirtualFieldMappings(Map<String, List<FieldMapping>> virtualToPhysicalMappings) {
        Map<String, List<FieldMapping>> fieldMappings = new HashMap<>();
        virtualToPhysicalMappings.values().stream()
                .flatMap((Function<List<FieldMapping>, Stream<FieldMapping>>) Collection::stream)
                .forEach(fieldMapping -> fieldMappings.put(fieldMapping.getTarget().getName(), ImmutableList.of(new FieldMapping(fieldMapping.getTarget(),
                        fieldMapping.getSource(),
                        fieldMapping.getVirtualIndexName(),
                        fieldMapping.getPhysicalIndexName(),
                        fieldMapping.getIndexType(),
                        fieldMapping.isContextAware()))));
        return fieldMappings;
    }

    private Map<DynamoSecondaryIndex, List<FieldMapping>> buildIndexPrimaryKeyFieldMappings(DynamoTableDescription virtualTable,
                                                                                            DynamoTableDescription physicalTable,
                                                                                            DynamoSecondaryIndexMapper secondaryIndexMapper) {
        Map<DynamoSecondaryIndex, List<FieldMapping>> secondaryIndexFieldMappings = new HashMap<>();
        for (DynamoSecondaryIndex virtualSI : virtualTable.getSIs()) {
            List<FieldMapping> fieldMappings = new ArrayList<>();
            try {
                DynamoSecondaryIndex physicalSI = secondaryIndexMapper.lookupPhysicalSecondaryIndex(virtualSI, physicalTable);
                fieldMappings.add(new FieldMapping(new Field(virtualSI.getPrimaryKey().getHashKey(),
                        virtualSI.getPrimaryKey().getHashKeyType()),
                        new Field(physicalSI.getPrimaryKey().getHashKey(),
                                physicalSI.getPrimaryKey().getHashKeyType()),
                        virtualSI.getIndexName(),
                        physicalSI.getIndexName(),
                        virtualSI.getType() == LSI ? TABLE : SECONDARYINDEX,
                        true));
                if (virtualSI.getPrimaryKey().getRangeKey().isPresent()) {
                    fieldMappings.add(new FieldMapping(new Field(virtualSI.getPrimaryKey().getRangeKey().get(),
                            virtualSI.getPrimaryKey().getRangeKeyType().get()),
                            new Field(physicalSI.getPrimaryKey().getRangeKey().get(),
                                    physicalSI.getPrimaryKey().getRangeKeyType().get()),
                            virtualSI.getIndexName(),
                            physicalSI.getIndexName(),
                            SECONDARYINDEX,
                            false));
                }
                secondaryIndexFieldMappings.put(virtualSI, fieldMappings);
            } catch (MappingException e) {
                throw new IllegalArgumentException("failure mapping virtual to physical " + virtualSI.getType() + ": " + e.getMessage() +
                        ", virtualSIPrimaryKey=" + virtualSI + ", virtualTable=" + virtualTable + ", physicalTable=" + physicalTable);
            }
        }
        return secondaryIndexFieldMappings;
    }

    /*
     * Helper method for adding a single FieldMapping to the existing list of FieldMapping's.
     */
    private void addFieldMapping(Map<String, List<FieldMapping>> fieldMappings, FieldMapping fieldMappingToAdd) {
        String key = fieldMappingToAdd.getSource().getName();
        List<FieldMapping> FieldMapping = fieldMappings.computeIfAbsent(key, k -> new ArrayList<>());
        FieldMapping.add(fieldMappingToAdd);
    }

    /*
     * Validate that the key schema elements match between the table's virtual and physical primary key as well as indexes.
     */
    private void validateVirtualPhysicalCompatibility() {
        // validate primary key
        try {
            validateCompatiblePrimaryKey(virtualTable.getPrimaryKey(), physicalTable.getPrimaryKey());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("invalid mapping virtual to physical table primary key: " + e.getMessage() +
                    ", virtualTable=" + virtualTable + ", physicalTable=" + physicalTable);
        }

        // validate secondary indexes
        validateSecondaryIndexes(virtualTable, physicalTable, secondaryIndexMapper);
    }

    @VisibleForTesting
    void validateSecondaryIndexes(DynamoTableDescription virtualTable,
                                  DynamoTableDescription physicalTable,
                                  DynamoSecondaryIndexMapper secondaryIndexMapper) {
        for (DynamoSecondaryIndex virtualSI : virtualTable.getSIs()) {
            DynamoSecondaryIndex physicalSI;
            // map the virtual index a physical one
            try {
                physicalSI = secondaryIndexMapper.lookupPhysicalSecondaryIndex(virtualSI, physicalTable);
            } catch (IllegalArgumentException | NullPointerException | MappingException e) {
                throw new IllegalArgumentException("failure mapping virtual to physical " + virtualSI.getType() + ": " + e.getMessage() +
                        ", virtualSIPrimaryKey=" + virtualSI + ", virtualTable=" + virtualTable + ", physicalTable=" + physicalTable);
            }
            try {
                // validate each virtual against the physical index that it was mapped to
                validateCompatiblePrimaryKey(virtualSI.getPrimaryKey(), physicalSI.getPrimaryKey());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("invalid mapping virtual to physical " + virtualSI.getType() + ": " + e.getMessage() +
                        ", virtualSIPrimaryKey=" + virtualSI.getPrimaryKey() + ", physicalSIPrimaryKey=" + physicalSI.getPrimaryKey() +
                        ", virtualTable=" + virtualTable + ", physicalTable=" + physicalTable);
            }
        }

        validateLSIMappings(virtualTable, physicalTable, secondaryIndexMapper);
    }

    /*
     * Validate that for any given physical LSI, there is no more than one virtual LSI that is mapped to it.
     */
    private void validateLSIMappings(DynamoTableDescription virtualTable,
                                     DynamoTableDescription physicalTable,
                                     DynamoSecondaryIndexMapper secondaryIndexMapper) {
        Map<DynamoSecondaryIndex, DynamoSecondaryIndex> usedPhysicalLSIs = new HashMap<>();
        virtualTable.getLSIs().forEach(virtualLSI -> {
            try {
                DynamoSecondaryIndex physicalLSI = secondaryIndexMapper.lookupPhysicalSecondaryIndex(virtualLSI, physicalTable);
                checkArgument(!usedPhysicalLSIs.containsKey(physicalLSI),
                        "two virtual LSI's(one:" + usedPhysicalLSIs.get(physicalLSI) + ", two:" +
                                virtualLSI + ", mapped to one physical LSI: " + physicalLSI);
                usedPhysicalLSIs.put(physicalLSI, virtualLSI);
            } catch (MappingException e) {
                throw new IllegalArgumentException("failure mapping virtual to physical " + virtualLSI.getType() + ": " + e.getMessage() +
                        ", virtualSIPrimaryKey=" + virtualLSI + ", virtualTable=" + virtualTable + ", physicalTable=" + physicalTable);
            }
        });
    }

    /*
     * Validates that virtual and physical indexes have hash keys with matching types.  If there is a range key on the
     * virtual index, then it also validates that the physical index also has one and their types match.
     */

    @VisibleForTesting
    void validateCompatiblePrimaryKey(PrimaryKey virtualPrimaryKey, PrimaryKey physicalPrimaryKey) throws IllegalArgumentException, NullPointerException {
        checkNotNull(virtualPrimaryKey.getHashKey(), "hashkey is required on virtual table");
        checkNotNull(physicalPrimaryKey.getHashKey(), "hashkey is required on physical table");
        checkArgument(physicalPrimaryKey.getHashKeyType() == S, "hashkey must be of type S");
        if (virtualPrimaryKey.getRangeKey().isPresent()) {
            checkArgument(physicalPrimaryKey.getRangeKey().isPresent(), "rangeKey exists on virtual primary key but not on physical");
            checkArgument(virtualPrimaryKey.getRangeKeyType().get() == physicalPrimaryKey.getRangeKeyType().get(),
                    "virtual and physical rangekey types mismatch");
        }
    }

    /*
     * Validate that the physical table's primary key and all of its secondary index's primary keys are of type S.
     */
    @VisibleForTesting
    void validatePhysicalTable(DynamoTableDescription physicalTableDescription) {
        String tableMsgPrefix = "physical table " + physicalTableDescription.getTableName() + "'s";
        validatePrimaryKey(physicalTableDescription.getPrimaryKey(), tableMsgPrefix);
        physicalTableDescription.getGSIs().forEach(dynamoSecondaryIndex ->
                validatePrimaryKey(dynamoSecondaryIndex.getPrimaryKey(),tableMsgPrefix + " GSI " + dynamoSecondaryIndex.getIndexName() + "'s"));
        physicalTableDescription.getLSIs().forEach(dynamoSecondaryIndex ->
                validatePrimaryKey(dynamoSecondaryIndex.getPrimaryKey(),tableMsgPrefix +  " LSI " + dynamoSecondaryIndex.getIndexName() + "'s"));
    }

    private void validatePrimaryKey(PrimaryKey primaryKey, String msgPrefix) {
        checkArgument(primaryKey.getHashKeyType() == S,
                      msgPrefix + " primary key hashkey must be type S, encountered type " +
                      primaryKey.getHashKeyType());
    }

    void setPhysicalTable(DynamoTableDescription physicalTable) {
        this.physicalTable = physicalTable;
    }

}
/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dynamodbv2.mt.mappers.metadata;

import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import org.apache.commons.lang.builder.EqualsBuilder;

import java.util.Optional;

/*
 * @author msgroi
 */
public class PrimaryKey {

    private final String hashKey;
    private final ScalarAttributeType hashKeyType;
    private final Optional<String> rangeKey;
    private final Optional<ScalarAttributeType> rangeKeyType;

    public PrimaryKey(String hashKey,
                      ScalarAttributeType hashKeyType) {
        this(hashKey, hashKeyType, Optional.empty(), Optional.empty());
    }

    public PrimaryKey(String hashKey,
                      ScalarAttributeType hashKeyType,
                      String rangeKey,
                      ScalarAttributeType rangeKeyType) {
        this(hashKey, hashKeyType, Optional.ofNullable(rangeKey), Optional.ofNullable(rangeKeyType));
    }

    public PrimaryKey(String hashKey,
                      ScalarAttributeType hashKeyType,
                      Optional<String> rangeKey,
                      Optional<ScalarAttributeType> rangeKeyType) {
        this.hashKey = hashKey;
        this.hashKeyType = hashKeyType;
        this.rangeKey = rangeKey;
        this.rangeKeyType = rangeKeyType;
    }

    public String getHashKey() {
        return hashKey;
    }

    public ScalarAttributeType getHashKeyType() {
        return hashKeyType;
    }

    public Optional<String> getRangeKey() {
        return rangeKey;
    }

    public Optional<ScalarAttributeType> getRangeKeyType() {
        return rangeKeyType;
    }

    @Override
    public String toString() {
        return "{" +
                "hashKey='" + hashKey + '\'' +
                ", hashKeyType=" + hashKeyType +
                (rangeKey.map(s -> ", rangeKey=" + s + ", rangeKeyType=" + rangeKeyType.get()).orElse("")) +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (o == null || getClass() != o.getClass()) return false;

        PrimaryKey that = (PrimaryKey) o;

        return new EqualsBuilder()
                .append(hashKey, that.hashKey)
                .append(hashKeyType, that.hashKeyType)
                .append(rangeKey, that.rangeKey)
                .append(rangeKeyType, that.rangeKeyType)
                .isEquals();
    }

}
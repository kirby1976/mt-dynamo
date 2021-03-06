/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dynamodbv2.mt.mappers.sharedtable.impl;

import com.salesforce.dynamodbv2.mt.mappers.sharedtable.impl.FieldPrefixFunction.FieldValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/*
 * @author msgroi
 */
class FieldPrefixFunctionTest {

    private static final FieldPrefixFunction sut = new FieldPrefixFunction(".");

    @Test
    void applyAndReverse() {
        FieldValue expected = new FieldValue("ctx", "table", "ctx.table.value", "value");

        FieldValue applied = sut.apply(() -> "ctx", "table", "value");

        assertEquals(expected, applied);

        assertEquals(expected, sut.reverse(applied.getQualifiedValue()));
    }

}
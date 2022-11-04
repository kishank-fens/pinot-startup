/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.segment.local.recordtransformer;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.pinot.common.utils.PinotDataType;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.data.FieldSpec;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.data.readers.GenericRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The {@code DataTypeTransformer} class will convert the values to follow the data types in {@link FieldSpec}.
 * <p>NOTE: should put this after all the values has been generated by other transformers (such as
 * {@link ExpressionTransformer}). After this, all values should be of the desired data types.
 */
@SuppressWarnings("rawtypes")
public class DataTypeTransformer implements RecordTransformer {
  private static final Logger LOGGER = LoggerFactory.getLogger(DataTypeTransformer.class);

  private final Map<String, PinotDataType> _dataTypes = new HashMap<>();
  private final boolean _continueOnError;

  public DataTypeTransformer(TableConfig tableConfig, Schema schema) {
    for (FieldSpec fieldSpec : schema.getAllFieldSpecs()) {
      if (!fieldSpec.isVirtualColumn()) {
        _dataTypes.put(fieldSpec.getName(), PinotDataType.getPinotDataTypeForIngestion(fieldSpec));
      }
    }
    if (tableConfig.getIngestionConfig() != null) {
      _continueOnError = tableConfig.getIngestionConfig().isContinueOnError();
    } else {
      _continueOnError = false;
    }
  }

  @Override
  public GenericRow transform(GenericRow record) {
    for (Map.Entry<String, PinotDataType> entry : _dataTypes.entrySet()) {
      String column = entry.getKey();
      try {
        Object value = record.getValue(column);
        if (value == null) {
          continue;
        }

        PinotDataType dest = entry.getValue();
        if (dest != PinotDataType.JSON) {
          value = standardize(column, value, dest.isSingleValue());
        }

        // NOTE: The standardized value could be null for empty Collection/Map/Object[].
        if (value == null) {
          record.putValue(column, null);
          continue;
        }

        // Convert data type if necessary
        PinotDataType source;
        if (value instanceof Object[]) {
          // Multi-value column
          Object[] values = (Object[]) value;
          source = PinotDataType.getMultiValueType(values[0].getClass());
        } else {
          // Single-value column
          source = PinotDataType.getSingleValueType(value.getClass());
        }
        // Skipping conversion when srcType!=destType is speculative, and can be unsafe when
        // the array for MV column contains values of mixing types. Mixing types can lead
        // to ClassCastException during conversion, often aborting data ingestion jobs.
        //
        // So now, calling convert() unconditionally for safety. Perf impact is negligible:
        // 1. for SV column, when srcType=destType, the conversion is simply pass through.
        // 2. for MV column, when srcType=destType, the conversion is simply pass through
        // if the source type is not Object[] (but sth like Integer[], Double[]). For Object[],
        // the conversion loops through values in the array like before, but can catch the
        // ClassCastException if it happens and continue the conversion now.
        value = dest.convert(value, source);
        value = dest.toInternal(value);

        record.putValue(column, value);
      } catch (Exception e) {
        if (!_continueOnError) {
          throw new RuntimeException("Caught exception while transforming data type for column: " + column, e);
        } else {
          LOGGER.debug("Caught exception while transforming data type for column: {}", column, e);
          record.putValue(column, null);
          record.putValue(GenericRow.INCOMPLETE_RECORD_KEY, true);
        }
      }
    }
    return record;
  }

  /**
   * Standardize the value into supported types.
   * <ul>
   *   <li>Empty Collection/Map/Object[] will be standardized to null</li>
   *   <li>Single-entry Collection/Map/Object[] will be standardized to single value (map key is ignored)</li>
   *   <li>Multi-entries Collection/Map/Object[] will be standardized to Object[] (map key is ignored)</li>
   * </ul>
   */
  @VisibleForTesting
  @Nullable
  static Object standardize(String column, @Nullable Object value, boolean isSingleValue) {
    if (value == null) {
      return null;
    }
    if (value instanceof Collection) {
      return standardizeCollection(column, (Collection) value, isSingleValue);
    }
    if (value instanceof Map) {
      return standardizeCollection(column, ((Map) value).values(), isSingleValue);
    }
    if (value instanceof Object[]) {
      Object[] values = (Object[]) value;
      int numValues = values.length;
      if (numValues == 0) {
        return null;
      }
      if (numValues == 1) {
        return standardize(column, values[0], isSingleValue);
      }
      List<Object> standardizedValues = new ArrayList<>(numValues);
      for (Object singleValue : values) {
        Object standardizedValue = standardize(column, singleValue, true);
        if (standardizedValue != null) {
          standardizedValues.add(standardizedValue);
        }
      }
      int numStandardizedValues = standardizedValues.size();
      if (numStandardizedValues == 0) {
        return null;
      }
      if (numStandardizedValues == 1) {
        return standardizedValues.get(0);
      }
      if (isSingleValue) {
        throw new IllegalArgumentException(
            "Cannot read single-value from Object[]: " + Arrays.toString(values) + " for column: " + column);
      }
      return standardizedValues.toArray();
    }
    return value;
  }

  private static Object standardizeCollection(String column, Collection collection, boolean isSingleValue) {
    int numValues = collection.size();
    if (numValues == 0) {
      return null;
    }
    if (numValues == 1) {
      return standardize(column, collection.iterator().next(), isSingleValue);
    }
    List<Object> standardizedValues = new ArrayList<>(numValues);
    for (Object singleValue : collection) {
      Object standardizedValue = standardize(column, singleValue, true);
      if (standardizedValue != null) {
        standardizedValues.add(standardizedValue);
      }
    }
    int numStandardizedValues = standardizedValues.size();
    if (numStandardizedValues == 0) {
      return null;
    }
    if (numStandardizedValues == 1) {
      return standardizedValues.get(0);
    }
    Preconditions.checkState(!isSingleValue, "Cannot read single-value from Collection: %s for column: %s", collection,
        column);
    return standardizedValues.toArray();
  }
}
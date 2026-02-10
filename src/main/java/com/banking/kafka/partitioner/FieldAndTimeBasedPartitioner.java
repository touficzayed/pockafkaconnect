/*
 * Original work Copyright (C) 2020 Can Elmas <canelm@gmail.com>
 * Modified work Copyright (C) 2026 Banking Kafka Connect POC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.banking.kafka.partitioner;

import io.confluent.connect.storage.common.StorageCommonConfig;
import io.confluent.connect.storage.errors.PartitionException;
import io.confluent.connect.storage.partitioner.PartitionerConfig;
import io.confluent.connect.storage.partitioner.TimeBasedPartitioner;
import io.confluent.connect.storage.util.DataUtils;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A partitioner that combines field-based and time-based partitioning.
 *
 * This partitioner extends TimeBasedPartitioner to add custom field extraction
 * for creating hierarchical directory structures like:
 * orgId=XXXX/appId=ZZZZ/year=2026/month=02/day=10/
 *
 * Configuration options:
 * - partition.field.name: List of fields to use for partitioning
 * - partition.field.format.path: Whether to include field names in path (default: true)
 *
 * @param <T> The type parameter for the partitioner
 */
public final class FieldAndTimeBasedPartitioner<T> extends TimeBasedPartitioner<T> {

    public static final String PARTITION_FIELD_FORMAT_PATH_CONFIG = "partition.field.format.path";
    public static final String PARTITION_FIELD_FORMAT_PATH_DOC =
            "Whether directory labels should be included when partitioning for custom fields. " +
            "When true: 'orgId=XXXX/appId=ZZZZ', when false: 'XXXX/ZZZZ'.";
    public static final String PARTITION_FIELD_FORMAT_PATH_DISPLAY = "Partition Field Format Path";
    public static final boolean PARTITION_FIELD_FORMAT_PATH_DEFAULT = true;

    private static final Logger log = LoggerFactory.getLogger(FieldAndTimeBasedPartitioner.class);

    private PartitionFieldExtractor partitionFieldExtractor;

    @Override
    @SuppressWarnings("unchecked")
    protected void init(long partitionDurationMs, String pathFormat, Locale locale,
                        DateTimeZone timeZone, Map<String, Object> config) {
        super.init(partitionDurationMs, pathFormat, locale, timeZone, config);

        final List<String> fieldNames = (List<String>) config.get(PartitionerConfig.PARTITION_FIELD_NAME_CONFIG);

        // Option value is parsed as string, need to handle both String and Boolean
        final Object formatPathValue = config.getOrDefault(PARTITION_FIELD_FORMAT_PATH_CONFIG, PARTITION_FIELD_FORMAT_PATH_DEFAULT);
        final boolean formatPath;
        if (formatPathValue instanceof Boolean) {
            formatPath = (Boolean) formatPathValue;
        } else if (formatPathValue instanceof String) {
            formatPath = Boolean.parseBoolean((String) formatPathValue);
        } else {
            formatPath = PARTITION_FIELD_FORMAT_PATH_DEFAULT;
        }

        this.partitionFieldExtractor = new PartitionFieldExtractor(fieldNames, formatPath);

        log.info("FieldAndTimeBasedPartitioner initialized with fields: {}, formatPath: {}",
                 fieldNames, formatPath);
    }

    @Override
    public String encodePartition(final SinkRecord sinkRecord, final long nowInMillis) {
        final String partitionsForTimestamp = super.encodePartition(sinkRecord, nowInMillis);
        final String partitionsForFields = this.partitionFieldExtractor.extract(sinkRecord);
        final String partition = String.join(this.delim, partitionsForFields, partitionsForTimestamp);

        if (log.isDebugEnabled()) {
            log.debug("Encoded partition: {}", partition);
        }

        return partition;
    }

    @Override
    public String encodePartition(final SinkRecord sinkRecord) {
        final String partitionsForTimestamp = super.encodePartition(sinkRecord);
        final String partitionsForFields = this.partitionFieldExtractor.extract(sinkRecord);
        final String partition = String.join(this.delim, partitionsForFields, partitionsForTimestamp);

        if (log.isDebugEnabled()) {
            log.debug("Encoded partition: {}", partition);
        }

        return partition;
    }

    /**
     * Extracts partition field values from records.
     */
    public static class PartitionFieldExtractor {

        private static final String DELIMITER_EQ = "=";

        private final boolean formatPath;
        private final List<String> fieldNames;

        PartitionFieldExtractor(final List<String> fieldNames, final boolean formatPath) {
            this.fieldNames = fieldNames;
            this.formatPath = formatPath;
        }

        public String extract(final ConnectRecord<?> record) {
            final Object value = record.value();

            if (value == null) {
                log.warn("Record value is null, cannot extract partition fields");
                throw new PartitionException("Record value is null");
            }

            final StringBuilder builder = new StringBuilder();

            for (final String fieldName : this.fieldNames) {
                if (builder.length() != 0) {
                    builder.append(StorageCommonConfig.DIRECTORY_DELIM_DEFAULT);
                }

                if (value instanceof Struct || value instanceof Map) {
                    final Object fieldValue = DataUtils.getNestedFieldValue(value, fieldName);

                    if (fieldValue == null) {
                        log.warn("Field '{}' is null in record", fieldName);
                        throw new PartitionException("Partition field '" + fieldName + "' is null");
                    }

                    final String partitionField = String.valueOf(fieldValue);

                    if (formatPath) {
                        builder.append(String.join(DELIMITER_EQ, fieldName, partitionField));
                    } else {
                        builder.append(partitionField);
                    }
                } else {
                    log.error("Value is not of Struct or Map type, got: {}",
                              value.getClass().getSimpleName());
                    throw new PartitionException("Value must be Struct or Map type for field partitioning");
                }
            }

            return builder.toString();
        }
    }
}

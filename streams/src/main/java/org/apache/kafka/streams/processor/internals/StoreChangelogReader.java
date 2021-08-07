/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StoreChangelogReader implements ChangelogReader {
    private static final Logger log = LoggerFactory.getLogger(StoreChangelogReader.class);

    private final String logPrefix;
    private final Consumer<byte[], byte[]> consumer;
    private final Map<TopicPartition, Long> endOffsets = new HashMap<>();
    private final Map<String, List<PartitionInfo>> partitionInfo = new HashMap<>();
    private final Map<TopicPartition, StateRestorer> stateRestorers = new HashMap<>();
    private final Map<TopicPartition, StateRestorer> needsRestoring = new HashMap<>();
    private final Map<TopicPartition, StateRestorer> needsInitializing = new HashMap<>();

    public StoreChangelogReader(final String threadId,
                                final Consumer<byte[], byte[]> consumer) {
        this.consumer = consumer;

        this.logPrefix = String.format("stream-thread [%s]", threadId);
    }

    public StoreChangelogReader(final Consumer<byte[], byte[]> consumer) {
        this("", consumer);
    }

    @Override
    public void register(final StateRestorer restorer) {
        if (!stateRestorers.containsKey(restorer.partition())) {
            stateRestorers.put(restorer.partition(), restorer);
            log.trace("Added restorer for changelog {}", restorer.partition());
        }
        needsInitializing.put(restorer.partition(), restorer);
    }

    public Collection<TopicPartition> restore(final Collection<StreamTask> restoringTasks) {
        if (!needsInitializing.isEmpty()) {
            initialize(restoringTasks);
        }

        if (needsRestoring.isEmpty()) {
            consumer.assign(Collections.<TopicPartition>emptyList());
            return completed();
        }

        final Set<TopicPartition> partitions = new HashSet<>(needsRestoring.keySet());
        final ConsumerRecords<byte[], byte[]> allRecords = consumer.poll(10);
        for (final TopicPartition partition : partitions) {
            restorePartition(allRecords, partition);
        }

        if (needsRestoring.isEmpty()) {
            consumer.assign(Collections.<TopicPartition>emptyList());
        }

        return completed();
    }

    private void initialize(final Collection<StreamTask> restoringTasks) {
        if (!consumer.subscription().isEmpty()) {
            throw new IllegalStateException("Restore consumer should not be subscribed to any topics (" + consumer.subscription() + ")");
        }

        // first refresh the changelog partition information from brokers, since initialize is only called when
        // the needsInitializing map is not empty, meaning we do not know the metadata for some of them yet
        refreshChangelogInfo();

        Map<TopicPartition, StateRestorer> initializable = new HashMap<>();
        for (Map.Entry<TopicPartition, StateRestorer> entry : needsInitializing.entrySet()) {
            final TopicPartition topicPartition = entry.getKey();
            if (hasPartition(topicPartition)) {
                initializable.put(entry.getKey(), entry.getValue());
            }
        }

        // try to fetch end offsets for the initializable restorers and remove any partitions
        // where we already have all of the data
        try {
            endOffsets.putAll(consumer.endOffsets(initializable.keySet()));
        } catch (final TimeoutException e) {
            // if timeout exception gets thrown we just give up this time and retry in the next run loop
            log.debug("{} Could not fetch end offset for {}; will fall back to partition by partition fetching", logPrefix, initializable);
            return;
        }

        final Iterator<TopicPartition> iter = initializable.keySet().iterator();
        while (iter.hasNext()) {
            final TopicPartition topicPartition = iter.next();
            final Long endOffset = endOffsets.get(topicPartition);

            // offset should not be null; but since the consumer API does not guarantee it
            // we add this check just in case
            if (endOffset != null) {
                final StateRestorer restorer = needsInitializing.get(topicPartition);
                if (restorer.checkpoint() >= endOffset) {
                    restorer.setRestoredOffset(restorer.checkpoint());
                    iter.remove();
                } else if (restorer.offsetLimit() == 0 || endOffset == 0) {
                    restorer.setRestoredOffset(0);
                    iter.remove();
                }
                needsInitializing.remove(topicPartition);
            } else {
                log.info("{} End offset cannot be found form the returned metadata; removing this partition from the current run loop", logPrefix);
                iter.remove();
            }
        }

        // set up restorer for those initializable
        if (!initializable.isEmpty()) {
            startRestoration(initializable, restoringTasks);
        }
    }

    private void startRestoration(final Map<TopicPartition, StateRestorer> initialized,
                                  final Collection<StreamTask> restoringTasks) {
        log.debug("{} Start restoring state stores from changelog topics {}", logPrefix, initialized.keySet());

        final Set<TopicPartition> assignment = new HashSet<>(consumer.assignment());
        assignment.addAll(initialized.keySet());
        consumer.assign(assignment);

        final List<StateRestorer> needsPositionUpdate = new ArrayList<>();
        for (final StateRestorer restorer : initialized.values()) {
            final TopicPartition restoringPartition = restorer.partition();
            if (restorer.checkpoint() != StateRestorer.NO_CHECKPOINT) {
                consumer.seek(restoringPartition, restorer.checkpoint());
                logRestoreOffsets(
                    restoringPartition,
                    restorer.checkpoint(),
                    endOffsets.get(restoringPartition));
                restorer.setStartingOffset(consumer.position(restoringPartition));
            } else {
                consumer.seekToBeginning(Collections.singletonList(restoringPartition));
                needsPositionUpdate.add(restorer);
            }
        }

        for (final StateRestorer restorer : needsPositionUpdate) {
            final TopicPartition restoringPartition = restorer.partition();

            for (final StreamTask task : restoringTasks) {
                if (task.changelogPartitions().contains(restoringPartition) || task.partitions().contains(restoringPartition)) {
                    if (task.eosEnabled) {
                        log.info("No checkpoint found for task {} state store {} changelog {} with EOS turned on. " +
                            "Reinitializing the task and restore its state from the beginning.", task.id, restorer.storeName(), restorer.partition());

                        needsInitializing.remove(restoringPartition);
                        restorer.setCheckpointOffset(consumer.position(restoringPartition));

                        task.reinitializeStateStoresForPartitions(restoringPartition);
                    } else {
                        log.info("Restoring task {}'s state store {} from beginning of the changelog {} ", task.id, restorer.storeName(), restorer.partition());

                        final long position = consumer.position(restoringPartition);
                        logRestoreOffsets(
                            restoringPartition,
                            position,
                            endOffsets.get(restoringPartition));
                        restorer.setStartingOffset(position);
                    }
                }
            }
        }

        needsRestoring.putAll(initialized);
    }

    private void logRestoreOffsets(final TopicPartition partition, final long startingOffset, final Long endOffset) {
        log.debug("{} Restoring partition {} from offset {} to endOffset {}",
                  logPrefix,
                  partition,
                  startingOffset,
                  endOffset);
    }

    private Collection<TopicPartition> completed() {
        final Set<TopicPartition> completed = new HashSet<>(stateRestorers.keySet());
        completed.removeAll(needsRestoring.keySet());
        log.trace("{} completed partitions {}", logPrefix, completed);
        return completed;
    }

    private void refreshChangelogInfo() {
        try {
            partitionInfo.putAll(consumer.listTopics());
        } catch (final TimeoutException e) {
            log.debug("{} Could not fetch topic metadata within the timeout, will retry in the next run loop", logPrefix);
        }
    }

    @Override
    public Map<TopicPartition, Long> restoredOffsets() {
        final Map<TopicPartition, Long> restoredOffsets = new HashMap<>();
        for (final Map.Entry<TopicPartition, StateRestorer> entry : stateRestorers.entrySet()) {
            final StateRestorer restorer = entry.getValue();
            if (restorer.isPersistent()) {
                restoredOffsets.put(entry.getKey(), restorer.restoredOffset());
            }
        }
        return restoredOffsets;
    }

    @Override
    public void reset() {
        partitionInfo.clear();
        stateRestorers.clear();
        needsRestoring.clear();
        endOffsets.clear();
        needsInitializing.clear();
    }

    private void restorePartition(final ConsumerRecords<byte[], byte[]> allRecords,
                                  final TopicPartition topicPartition) {
        final StateRestorer restorer = stateRestorers.get(topicPartition);
        final Long endOffset = endOffsets.get(topicPartition);
        final long pos = processNext(allRecords.records(topicPartition), restorer, endOffset);
        restorer.setRestoredOffset(pos);
        if (restorer.hasCompleted(pos, endOffset)) {
            if (pos > endOffset + 1) {
                throw new IllegalStateException(
                        String.format("Log end offset of %s should not change while restoring: old end offset %d, current offset %d",
                                      topicPartition,
                                      endOffset,
                                      pos));
            }

            log.debug("{} Completed restoring state from changelog {} with {} records ranging from offset {} to {}",
                    logPrefix,
                    topicPartition,
                    restorer.restoredNumRecords(),
                    restorer.startingOffset(),
                    restorer.restoredOffset());

            needsRestoring.remove(topicPartition);
        }
    }

    private long processNext(final List<ConsumerRecord<byte[], byte[]>> records,
                             final StateRestorer restorer,
                             final Long endOffset) {
        for (final ConsumerRecord<byte[], byte[]> record : records) {
            final long offset = record.offset();
            if (restorer.hasCompleted(offset, endOffset)) {
                return offset;
            }
            if (record.key() != null) {
                restorer.restore(record.key(), record.value());
            }
        }
        return consumer.position(restorer.partition());
    }

    private boolean hasPartition(final TopicPartition topicPartition) {
        final List<PartitionInfo> partitions = partitionInfo.get(topicPartition.topic());

        if (partitions == null) {
            return false;
        }

        for (final PartitionInfo partition : partitions) {
            if (partition.partition() == topicPartition.partition()) {
                return true;
            }
        }

        return false;
    }
}

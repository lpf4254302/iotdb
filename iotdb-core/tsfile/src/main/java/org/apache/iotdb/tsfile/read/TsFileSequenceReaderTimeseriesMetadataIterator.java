/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.tsfile.read;

import org.apache.iotdb.tsfile.exception.TsFileSequenceReaderTimeseriesMetadataIteratorException;
import org.apache.iotdb.tsfile.file.metadata.MetadataIndexEntry;
import org.apache.iotdb.tsfile.file.metadata.MetadataIndexNode;
import org.apache.iotdb.tsfile.file.metadata.TimeseriesMetadata;
import org.apache.iotdb.tsfile.file.metadata.enums.MetadataIndexNodeType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class TsFileSequenceReaderTimeseriesMetadataIterator
    implements Iterator<Map<String, List<TimeseriesMetadata>>> {

  private static final int MAX_TIMESERIES_METADATA_COUNT = 2000;
  private final TsFileSequenceReader reader;
  private final boolean needChunkMetadata;
  private ByteBuffer currentBuffer = null;
  private final Deque<MetadataIndexEntryInfo> metadataIndexEntryStack = new ArrayDeque<>();
  private String currentDeviceId;
  private int currentTimeseriesMetadataCount = 0;

  public TsFileSequenceReaderTimeseriesMetadataIterator(
      TsFileSequenceReader reader, boolean needChunkMetadata) throws IOException {
    this.reader = reader;

    if (this.reader.tsFileMetaData == null) {
      this.reader.readFileMetadata();
    }

    final MetadataIndexNode metadataIndexNode = reader.tsFileMetaData.getMetadataIndex();
    long curEntryEndOffset = metadataIndexNode.getEndOffset();
    List<MetadataIndexEntry> metadataIndexEntryList = metadataIndexNode.getChildren();
    this.needChunkMetadata = needChunkMetadata;

    for (int i = metadataIndexEntryList.size() - 1; i >= 0; i--) {
      metadataIndexEntryStack.push(
          new MetadataIndexEntryInfo(
              metadataIndexEntryList.get(i), metadataIndexNode.getNodeType(), curEntryEndOffset));
      curEntryEndOffset = metadataIndexEntryList.get(i).getOffset();
    }
  }

  @Override
  public boolean hasNext() {
    return !metadataIndexEntryStack.isEmpty()
        || (currentBuffer != null && currentBuffer.hasRemaining());
  }

  @Override
  public Map<String, List<TimeseriesMetadata>> next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    final Map<String, List<TimeseriesMetadata>> timeseriesMetadataMap = new HashMap<>();

    while (currentTimeseriesMetadataCount < MAX_TIMESERIES_METADATA_COUNT) {
      // 1. Check Buffer
      // currentTimeseriesMetadataCount has reached the limit in the previous
      // loop and maybe there is still some data that remains in the buffer.
      if (currentBuffer != null && currentBuffer.hasRemaining()) {
        timeseriesMetadataMap
            .computeIfAbsent(currentDeviceId, k -> new ArrayList<>())
            .addAll(deserializeTimeseriesMetadata());
      }

      if (currentTimeseriesMetadataCount >= MAX_TIMESERIES_METADATA_COUNT
          || metadataIndexEntryStack.isEmpty()) {
        break;
      }

      // 2. Deserialize MetadataIndexEntry
      final MetadataIndexEntryInfo indexEntryInfo = metadataIndexEntryStack.pop();

      try {
        deserializeMetadataIndexEntry(indexEntryInfo, timeseriesMetadataMap);
      } catch (IOException e) {
        throw new TsFileSequenceReaderTimeseriesMetadataIteratorException(
            String.format(
                "TsFileSequenceReaderTimeseriesMetadataIterator: deserializeMetadataIndexEntry failed, "
                    + "MetadataIndexEntryInfo: %s, "
                    + e.getMessage(),
                indexEntryInfo));
      }
    }

    // 3. Reset currentTimeseriesMetadataCount
    if (currentTimeseriesMetadataCount >= MAX_TIMESERIES_METADATA_COUNT) {
      currentTimeseriesMetadataCount = 0;
    }

    return timeseriesMetadataMap;
  }

  private void deserializeMetadataIndexEntry(
      MetadataIndexEntryInfo metadataIndexEntryInfo,
      Map<String, List<TimeseriesMetadata>> timeseriesMetadataMap)
      throws IOException {
    if (metadataIndexEntryInfo
        .getMetadataIndexNodeType()
        .equals(MetadataIndexNodeType.LEAF_MEASUREMENT)) {
      deserializeLeafMeasurement(
          metadataIndexEntryInfo.getMetadataIndexEntry(),
          metadataIndexEntryInfo.getEndOffset(),
          timeseriesMetadataMap);

    } else {
      deserializeInternalNode(
          metadataIndexEntryInfo.getMetadataIndexEntry(),
          metadataIndexEntryInfo.getEndOffset(),
          metadataIndexEntryInfo
              .getMetadataIndexNodeType()
              .equals(MetadataIndexNodeType.LEAF_DEVICE));
    }
  }

  private void deserializeLeafMeasurement(
      MetadataIndexEntry metadataIndexEntry,
      long endOffset,
      Map<String, List<TimeseriesMetadata>> timeseriesMetadataMap)
      throws IOException {
    if (currentBuffer != null && currentBuffer.hasRemaining()) {
      throw new TsFileSequenceReaderTimeseriesMetadataIteratorException(
          "currentBuffer still has some data left before deserializeLeafMeasurement");
    }

    currentBuffer = reader.readData(metadataIndexEntry.getOffset(), endOffset);

    timeseriesMetadataMap
        .computeIfAbsent(currentDeviceId, k -> new ArrayList<>())
        .addAll(deserializeTimeseriesMetadata());
  }

  private List<TimeseriesMetadata> deserializeTimeseriesMetadata() {
    final List<TimeseriesMetadata> timeseriesMetadataList = new ArrayList<>();
    while (currentBuffer.hasRemaining()
        && currentTimeseriesMetadataCount < MAX_TIMESERIES_METADATA_COUNT) {
      timeseriesMetadataList.add(
          TimeseriesMetadata.deserializeFrom(currentBuffer, needChunkMetadata));
      currentTimeseriesMetadataCount++;
    }
    return timeseriesMetadataList;
  }

  private void deserializeInternalNode(
      MetadataIndexEntry metadataIndexEntry, long endOffset, boolean isLeafDevice)
      throws IOException {
    if (isLeafDevice) {
      currentDeviceId = metadataIndexEntry.getName();
    }

    final MetadataIndexNode metadataIndexNode =
        MetadataIndexNode.deserializeFrom(
            reader.readData(metadataIndexEntry.getOffset(), endOffset));
    MetadataIndexNodeType metadataIndexNodeType = metadataIndexNode.getNodeType();
    List<MetadataIndexEntry> children = metadataIndexNode.getChildren();
    long curEntryEndOffset = metadataIndexNode.getEndOffset();

    for (int i = children.size() - 1; i >= 0; i--) {
      metadataIndexEntryStack.push(
          new MetadataIndexEntryInfo(children.get(i), metadataIndexNodeType, curEntryEndOffset));
      curEntryEndOffset = children.get(i).getOffset();
    }
  }

  private static class MetadataIndexEntryInfo {
    private final MetadataIndexEntry metadataIndexEntry;
    private final MetadataIndexNodeType metadataIndexNodeType;
    private final long endOffset;

    public MetadataIndexEntryInfo(
        MetadataIndexEntry metadataIndexEntry,
        MetadataIndexNodeType metadataIndexNodeType,
        long endOffset) {
      this.metadataIndexEntry = metadataIndexEntry;
      this.metadataIndexNodeType = metadataIndexNodeType;
      this.endOffset = endOffset;
    }

    public MetadataIndexEntry getMetadataIndexEntry() {
      return metadataIndexEntry;
    }

    public MetadataIndexNodeType getMetadataIndexNodeType() {
      return metadataIndexNodeType;
    }

    public long getEndOffset() {
      return endOffset;
    }
  }
}

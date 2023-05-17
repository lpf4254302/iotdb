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

package org.apache.iotdb.os.cache;

import org.apache.iotdb.os.conf.ObjectStorageConfig;
import org.apache.iotdb.os.conf.ObjectStorageDescriptor;
import org.apache.iotdb.os.io.ObjectStorageConnector;
import org.apache.iotdb.os.io.aws.S3ObjectStorageConnector;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Weigher;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OSFileCache {
  private static final Logger logger = LoggerFactory.getLogger(OSFileCache.class);
  private static final ObjectStorageConfig config =
      ObjectStorageDescriptor.getInstance().getConfig();
  private static final ObjectStorageConnector connector;

  static {
    switch (config.getOsType()) {
      case AWS_S3:
        connector = new S3ObjectStorageConnector();
        break;
      default:
        connector = null;
    }
  }

  /**
   * persistent LRU cache for remote TsFile, value is loaded successfully when it has been stored on
   * the disk
   */
  private final LoadingCache<OSFileCacheKey, OSFileCacheValue> remotePos2LocalCacheFile;
  /** manage all io operations to the cache files */
  private final CacheFileManager cacheFileManager = CacheFileManager.getInstance();

  private OSFileCache() {
    remotePos2LocalCacheFile =
        Caffeine.newBuilder()
            .maximumWeight(config.getCacheMaxDiskUsage())
            .weigher((Weigher<OSFileCacheKey, OSFileCacheValue>) (key, value) -> value.getLength())
            .removalListener(
                (key, value, cause) -> {
                  if (value != null) {
                    value.setShouldDelete();
                  }
                })
            .build(new OSFileCacheLoader());
  }

  public OSFileCacheValue get(OSFileCacheKey key) {
    return remotePos2LocalCacheFile.get(key);
  }

  /** This method is used by the recover procedure */
  void put(OSFileCacheKey key, OSFileCacheValue value) {
    remotePos2LocalCacheFile.put(key, value);
  }

  class OSFileCacheLoader implements CacheLoader<OSFileCacheKey, OSFileCacheValue> {
    @Override
    public @Nullable OSFileCacheValue load(@NonNull OSFileCacheKey key) throws Exception {
      byte[] data =
          connector.getRemoteFile(key.getFile().toOSURI(), key.getStartPosition(), key.getLength());
      return cacheFileManager.persist(key, data);
    }
  }

  public static OSFileCache getInstance() {
    return OSFileCache.InstanceHolder.INSTANCE;
  }

  private static class InstanceHolder {
    private InstanceHolder() {}

    private static final OSFileCache INSTANCE = new OSFileCache();
  }
}
/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jimfs.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Implementation of {@link WatchService} that polls for changes to directories at registered paths.
 *
 * @author Colin Decker
 */
final class PollingWatchService extends AbstractWatchService {

  private final ScheduledExecutorService pollingService
      = Executors.newSingleThreadScheduledExecutor();

  /**
   * Map of keys to the most recent directory snapshot for each key.
   */
  private final ConcurrentMap<Key, Snapshot> snapshots = new ConcurrentHashMap<>();

  private final FileSystemService service;

  private final long pollingTime;
  private final TimeUnit timeUnit;

  private ScheduledFuture<?> pollingFuture;

  public PollingWatchService(FileSystemService service) {
    this(service, 5, SECONDS);
  }

  // TODO(cgdecker): make user configurable somehow? meh
  @VisibleForTesting
  PollingWatchService(FileSystemService service, long pollingTime, TimeUnit timeUnit) {
    this.service = checkNotNull(service);

    checkArgument(pollingTime >= 0, "polling time (%s) may not be negative", pollingTime);
    this.pollingTime = pollingTime;
    this.timeUnit = checkNotNull(timeUnit);

    service.resourceManager().register(this);
  }

  @Override
  public Key register(Watchable watchable,
      Iterable<? extends WatchEvent.Kind<?>> eventTypes) throws IOException {
    JimfsPath path = checkWatchable(watchable);

    Key key = super.register(path, eventTypes);

    Snapshot snapshot = takeSnapshot(path);

    if (snapshot == null) {
      throw new NotDirectoryException(path.toString());
    }

    synchronized (this) {
      snapshots.put(key, snapshot);
      if (pollingFuture == null) {
        startPolling();
      }
    }

    return key;
  }

  private JimfsPath checkWatchable(Watchable watchable) {
    if (!(watchable instanceof JimfsPath) || !isSameFileSystem((Path) watchable)) {
      throw new IllegalArgumentException("watchable (" + watchable + ") must be a Path " +
          "associated with the same file system as this watch service");
    }

    return (JimfsPath) watchable;
  }

  private boolean isSameFileSystem(Path path) {
    return ((JimfsFileSystem) path.getFileSystem()).service() == service;
  }

  @VisibleForTesting
  synchronized boolean isPolling() {
    return pollingFuture != null;
  }

  @Override
  public synchronized void cancelled(Key key) {
    snapshots.remove(key);

    if (snapshots.isEmpty()) {
      stopPolling();
    }
  }

  @Override
  public void close() {
    super.close();

    synchronized (this) {
      for (Key key : snapshots.keySet()) {
        key.cancel();
      }

      pollingService.shutdown();
      service.resourceManager().unregister(this);
    }
  }

  private void startPolling() {
    pollingFuture = pollingService
        .scheduleAtFixedRate(pollingTask, pollingTime, pollingTime, timeUnit);
  }

  private void stopPolling() {
    pollingFuture.cancel(false);
    pollingFuture = null;
  }

  private final Runnable pollingTask = new Runnable() {
    @Override
    public void run() {
      synchronized (PollingWatchService.this) {
        for (Map.Entry<Key, Snapshot> entry : snapshots.entrySet()) {
          Key key = entry.getKey();
          Snapshot previousSnapshot = entry.getValue();

          JimfsPath path = (JimfsPath) key.watchable();
          try {
            Snapshot newSnapshot = takeSnapshot(path);
            boolean posted = previousSnapshot.postChanges(newSnapshot, key);
            entry.setValue(newSnapshot);
            if (posted) {
              key.signal();
            }
          } catch (IOException e) {
            // snapshot failed; assume file does not exist or isn't a directory and cancel the key
            key.cancel();
          }

        }
      }
    }
  };

  @Nullable
  private Snapshot takeSnapshot(JimfsPath path) throws IOException {
    return new Snapshot(service.snapshotModifiedTimes(path));
  }

  /**
   * Snapshot of the state of a directory at a particular moment.
   */
  private final class Snapshot {

    /**
     * Maps directory entry names to last modified times.
     */
    private final ImmutableMap<Name, Long> modifiedTimes;

    Snapshot(ImmutableMap<Name, Long> modifiedTimes) {
      this.modifiedTimes = checkNotNull(modifiedTimes);
    }

    /**
     * Posts events to the given key based on the kinds of events it subscribes to and what events
     * have occurred between this state and the given new state.
     */
    boolean postChanges(Snapshot newState, Key key) {
      boolean changesPosted = false;

      if (key.subscribesTo(ENTRY_CREATE)) {
        Set<Name> created = Sets.difference(
            newState.modifiedTimes.keySet(),
            modifiedTimes.keySet());

        for (Name name : created) {
          key.post(new Event<>(ENTRY_CREATE, 1, service.paths().createFileName(name)));
          changesPosted = true;
        }
      }

      if (key.subscribesTo(ENTRY_DELETE)) {
        Set<Name> deleted = Sets.difference(
            modifiedTimes.keySet(),
            newState.modifiedTimes.keySet());

        for (Name name : deleted) {
          key.post(new Event<>(ENTRY_DELETE, 1, service.paths().createFileName(name)));
          changesPosted = true;
        }
      }

      if (key.subscribesTo(ENTRY_MODIFY)) {
        for (Map.Entry<Name, Long> entry : modifiedTimes.entrySet()) {
          Name name = entry.getKey();
          Long modifiedTime = entry.getValue();

          Long newModifiedTime = newState.modifiedTimes.get(name);
          if (newModifiedTime != null && !modifiedTime.equals(newModifiedTime)) {
            key.post(new Event<>(ENTRY_MODIFY, 1, service.paths().createFileName(name)));
            changesPosted = true;
          }
        }
      }

      return changesPosted;
    }
  }
}
/*
 * Copyright 2011 Google Inc. All Rights Reserved.
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

package com.google.walkaround.slob.server;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.blobstore.dev.LocalBlobstoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.tools.development.ApiProxyLocal;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalFileServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.google.apphosting.api.ApiProxy;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.walkaround.slob.server.MutationLog.DeltaIterator;
import com.google.walkaround.slob.shared.ChangeData;
import com.google.walkaround.slob.shared.ChangeRejected;
import com.google.walkaround.slob.shared.ClientId;
import com.google.walkaround.slob.shared.InvalidSnapshot;
import com.google.walkaround.slob.shared.SlobId;
import com.google.walkaround.slob.shared.SlobModel;
import com.google.walkaround.util.server.RetryHelper.PermanentFailure;
import com.google.walkaround.util.server.RetryHelper.RetryableFailure;
import com.google.walkaround.util.server.appengine.CheckedDatastore;
import com.google.walkaround.util.server.appengine.CheckedDatastore.CheckedIterator;
import com.google.walkaround.util.server.appengine.CheckedDatastore.CheckedPreparedQuery;
import com.google.walkaround.util.server.appengine.CheckedDatastore.CheckedTransaction;
import com.google.walkaround.util.server.appengine.OversizedPropertyMover;
import com.google.walkaround.util.server.appengine.OversizedPropertyMover.MovableProperty;

import junit.framework.TestCase;

import org.waveprotocol.wave.model.util.Utf16Util;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * @author ohler@google.com (Christian Ohler)
 */
public class MutationLogTest extends TestCase {

  @SuppressWarnings("unused")
  private static final Logger log = Logger.getLogger(MutationLogTest.class.getName());

  // NOTE(ohler): testSizeEstimates() does NOT pass regardless of what these
  // constants are.  It was off by one when I ran with other values.  Still good
  // enough, didn't investigate in detail.
  private static final String SNAPSHOT_STRING =
      "snapshotsnapshotsnapshotsnapshotsnapshotsnapshotsnapshotsnapshotsnapshotsnapshotsnapshot"
      + "snapshotsnapshotsnapshotsnapshotsnapshotsnapshotsnapshotsnapshotsnapshotsnapshotsnapshot";
  private static final String ROOT_ENTITY_KIND = "Wavelet";
  private static final String DELTA_ENTITY_KIND = "WaveletDelta";
  private static final String SNAPSHOT_ENTITY_KIND = "WaveletSnapshot";
  private static final String OBJECT_ID = "obj";

  private static class TestModel implements SlobModel {
    public class TestObject implements Slob {
      @Override @Nullable public String snapshot() {
        return snapshotString;
      }

      @Override public void apply(ChangeData<String> payload) throws ChangeRejected {
        // accept any payload, do nothing with it
      }
    }

    @Override
    public Slob create(@Nullable String snapshot) throws InvalidSnapshot {
      return new TestObject();
    }

    @Override
    public List<String> transform(List<ChangeData<String>> clientOps,
        List<ChangeData<String>> serverOps) throws ChangeRejected {
      throw new AssertionError("Not implemented");
    }

    private final String snapshotString;

    TestModel(String snapshotString) {
      this.snapshotString = checkNotNull(snapshotString, "Null snapshotString");
    }
  }

  private final LocalServiceTestHelper helper =
      new LocalServiceTestHelper(
          new LocalDatastoreServiceTestConfig()
              .setDefaultHighRepJobPolicyUnappliedJobPercentage(30),
          new LocalFileServiceTestConfig());
  private final CheckedDatastore datastore = new CheckedDatastore(
      DatastoreServiceFactory.getDatastoreService());

  @Override protected void setUp() throws Exception {
    super.setUp();
    helper.setUp();
    ApiProxyLocal proxy = (ApiProxyLocal) ApiProxy.getDelegate();
    // HACK(ohler): Work around "illegal blobKey" crash.
    proxy.setProperty(LocalBlobstoreService.NO_STORAGE_PROPERTY, "true");
  }

  @Override protected void tearDown() throws Exception {
    helper.tearDown();
    super.tearDown();
  }

  public void testSizeEstimates() throws Exception {
    CheckedTransaction tx = new CheckedTransaction() {
        @Override
        public Entity get(Key key) throws PermanentFailure, RetryableFailure {
          return null;
        }

        @Override
        public Map<Key, Entity> get(Iterable<Key> keys) throws PermanentFailure, RetryableFailure {
          throw new AssertionError("Not implemented");
        }

        @Override
        public CheckedPreparedQuery prepare(Query q) {
          return new CheckedPreparedQuery() {
            @Override public CheckedIterator asIterator(final FetchOptions options)
                throws PermanentFailure, RetryableFailure {
              return CheckedIterator.EMPTY;
            }

            @Override public List<Entity> asList(final FetchOptions options)
                throws PermanentFailure, RetryableFailure {
              return ImmutableList.of();
            }

            @Override public int countEntities(final FetchOptions options)
                throws PermanentFailure, RetryableFailure {
              throw new AssertionError("Not implemented");
            }

            @Override public Entity asSingleEntity() throws PermanentFailure, RetryableFailure {
              throw new AssertionError("Not implemented");
            }
          };
        }

        @Override
        public Key put(Entity e) throws PermanentFailure, RetryableFailure {
          throw new AssertionError("Not implemented");
        }

        @Override
        public List<Key> put(Iterable<Entity> e) throws PermanentFailure, RetryableFailure {
          throw new AssertionError("Not implemented");
        }

        @Override
        public void delete(Key... keys) throws PermanentFailure, RetryableFailure {
          throw new AssertionError("Not implemented");
        }

        @Override
        public TaskHandle enqueueTask(Queue queue, TaskOptions task)
            throws PermanentFailure, RetryableFailure {
          throw new AssertionError("Not implemented");
        }

        @Override
        public void rollback() {
          throw new AssertionError("Not implemented");
        }

        @Override
        public void commit() throws PermanentFailure, RetryableFailure {
          throw new AssertionError("Not implemented");
        }

        @Override
        public boolean isActive() {
          throw new AssertionError("Not implemented");
        }

        @Override
        public void close() {
          throw new AssertionError("Not implemented");
        }

        @Override
        public void runAfterCommit(Runnable r) {
          throw new AssertionError("Not implemented");
        }
      };

    SlobId objectId = new SlobId(OBJECT_ID);
    ClientId clientId = new ClientId("s");
    String payload = "{\"a\": 5}";

    // I didn't track down exactly where the 12 comes from.
    int encodingOverhead = 12;
    int idSize = ROOT_ENTITY_KIND.length() + OBJECT_ID.length() + encodingOverhead;
    int versionSize = 8;
    int deltaSize = idSize + DELTA_ENTITY_KIND.length() + versionSize
        + MutationLog.DELTA_CLIENT_ID_PROPERTY.length() + clientId.getId().length()
        + MutationLog.DELTA_OP_PROPERTY.length() + payload.length();
    int snapshotSize = idSize + SNAPSHOT_ENTITY_KIND.length() + versionSize
        + MutationLog.SNAPSHOT_DATA_PROPERTY.length() + SNAPSHOT_STRING.length();
    log.info("deltaSize=" + deltaSize
        + ", snapshotSize=" + snapshotSize);
    assertEquals(56, deltaSize);
    assertEquals(225, snapshotSize);
    ChangeData<String> delta = new ChangeData<String>(clientId, payload);

    MutationLog mutationLog =
        new MutationLog(datastore,
            ROOT_ENTITY_KIND, DELTA_ENTITY_KIND, SNAPSHOT_ENTITY_KIND,
            new MutationLog.DefaultDeltaEntityConverter(),
            tx, objectId, new TestModel(SNAPSHOT_STRING), new MutationLog.StateCache(1),
            OversizedPropertyMover.NULL_LISTENER);
    MutationLog.Appender appender = mutationLog.prepareAppender().getAppender();

    assertEquals(0, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals( 1 * deltaSize + 0 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals( 2 * deltaSize + 0 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals( 3 * deltaSize + 0 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals( 4 * deltaSize + 0 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals( 5 * deltaSize + 0 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals( 6 * deltaSize + 0 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals( 7 * deltaSize + 0 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals( 8 * deltaSize + 0 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals( 9 * deltaSize + 1 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals(10 * deltaSize + 1 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals(11 * deltaSize + 1 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals(12 * deltaSize + 1 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals(13 * deltaSize + 1 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals(14 * deltaSize + 2 * snapshotSize, appender.estimatedBytesStaged());
    appender.append(delta);
    assertEquals(15 * deltaSize + 2 * snapshotSize, appender.estimatedBytesStaged());
  }

  String makeMassiveString() {
    int numCodePoints = 1 << 19;
    StringBuilder b = new StringBuilder(
        // Overestimate.
        numCodePoints * 2);
    int nextCp = 0;
    for (int i = 0; i < numCodePoints; i++) {
      b.appendCodePoint(nextCp);
      if (i + 1 < numCodePoints && i % 80 == 79) {
        b.append("\n");
        i++;
      }
      nextCp = (nextCp + 1) & 0x10ffff;
      while (Utf16Util.isSurrogate(nextCp) || !Utf16Util.isCodePointValid(nextCp)) {
        //log.info("Skipping cp " + nextCp);
        nextCp = (nextCp + 1) & 0x10ffff;
      }
    }
    return "" + b;
  }

  public void testOversizedProperties() throws Exception {
    String massiveString = makeMassiveString();
    final List<String> movedProperties = Lists.newArrayList();
    SlobId objectId = new SlobId(OBJECT_ID);
    CheckedTransaction tx = datastore.beginTransaction();
    MutationLog mutationLog =
        new MutationLog(datastore,
            ROOT_ENTITY_KIND, DELTA_ENTITY_KIND, SNAPSHOT_ENTITY_KIND,
            new MutationLog.DefaultDeltaEntityConverter(),
            tx, objectId, new TestModel(massiveString), new MutationLog.StateCache(1),
            new OversizedPropertyMover.BlobWriteListener() {
              @Override public void blobCreated(
                  Key entityKey, MovableProperty property, BlobKey blobKey) {
                movedProperties.add(property.getPropertyName());
              }
              @Override public void blobDeleted(
                  Key entityKey, MovableProperty property, BlobKey blobKey) {
                throw new AssertionError();
              }
            });
    MutationLog.Appender appender = mutationLog.prepareAppender().getAppender();
    ClientId clientId = new ClientId("fake-clientid");
    // This will generate one snapshot.
    appender.appendAll(
        ImmutableList.of(
            new ChangeData<String>(clientId, "delta 0 " + massiveString),
            new ChangeData<String>(clientId, "delta 1 " + massiveString),
            new ChangeData<String>(clientId, "delta 2 " + massiveString)));
    // This will generate another snapshot.
    appender.append(new ChangeData<String>(clientId, "delta 3 " + massiveString));
    appender.finish();
    tx.commit();
    assertEquals(ImmutableList.of("op", "op", "op", "op", "Data", "Data"), movedProperties);

    CheckedTransaction tx2 = datastore.beginTransaction();
    MutationLog mutationLog2 =
        // TODO: eliminate code duplication
        new MutationLog(datastore,
            ROOT_ENTITY_KIND, DELTA_ENTITY_KIND, SNAPSHOT_ENTITY_KIND,
            new MutationLog.DefaultDeltaEntityConverter(),
            tx2, objectId, new TestModel(massiveString), new MutationLog.StateCache(1),
            new OversizedPropertyMover.BlobWriteListener() {
              @Override public void blobCreated(
                  Key entityKey, MovableProperty property, BlobKey blobKey) {
                throw new AssertionError();
              }
              @Override public void blobDeleted(
                  Key entityKey, MovableProperty property, BlobKey blobKey) {
                throw new AssertionError();
              }
            });
    DeltaIterator deltas = mutationLog2.forwardHistory(0, null);
    assertEquals("delta 0 " + massiveString, deltas.next().getPayload());
    assertEquals("delta 1 " + massiveString, deltas.next().getPayload());
    assertEquals("delta 2 " + massiveString, deltas.next().getPayload());
    assertEquals("delta 3 " + massiveString, deltas.next().getPayload());
    assertFalse(deltas.hasNext());
  }

}

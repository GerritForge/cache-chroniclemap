<<<<<<< PATCH SET (a38ca640455e8df792624d92dff13e31ce9874b9 Fix the instantiation of TestMetricMaker)
// Copyright (C) 2025 GerritForge, Inc.
//
// Licensed under the BSL 1.1 (the "License");
// you may not use this file except in compliance with the License.
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.gerritforge.gerrit.modules.cache.chroniclemap;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.TestMetricMaker;
import com.google.gerrit.server.cache.serialize.ObjectIdCacheSerializer;
import java.nio.ByteBuffer;
import net.openhft.chronicle.bytes.Bytes;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.Test;

public class TimedValueMarshallerTest {
  private static final String TEST_CACHE_NAME = "timed-value-cache";

  @Before
  public void setup() {
    CacheSerializers.registerCacheValueSerializer(
        TEST_CACHE_NAME, ObjectIdCacheSerializer.INSTANCE);
  }

  @Test
  public void shouldSerializeAndDeserializeBack() {
    ObjectId id = ObjectId.fromString("1234567890123456789012345678901234567890");
    long timestamp = 1600329018L;
    TimedValueMarshaller<ObjectId> marshaller =
        new TimedValueMarshaller<>(TestMetricMaker.getInstance(), TEST_CACHE_NAME);

    final TimedValue<ObjectId> wrapped = new TimedValue<>(id, timestamp);

    Bytes<ByteBuffer> out = Bytes.elasticByteBuffer();
    marshaller.write(out, wrapped);
    final TimedValue<ObjectId> actual = marshaller.read(out, null);
    assertThat(actual).isEqualTo(wrapped);
  }
}
=======
>>>>>>> BASE      (51cf9ec331701ac2b971a97e2d66a6e0f732b2f5 Add changes_by_project in the default list of persistent cac)

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

import com.google.gerrit.server.cache.serialize.ObjectIdCacheSerializer;
import com.googlesource.gerrit.modules.cache.chroniclemap.KeyWrapper;
import java.nio.ByteBuffer;
import net.openhft.chronicle.bytes.Bytes;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.Test;

public class KeyWrapperMarshallerTest {
  private static final String TEST_CACHE_NAME = "key-wrapper-test";

  @Before
  public void setup() {
    CacheSerializers.registerCacheKeySerializer(TEST_CACHE_NAME, ObjectIdCacheSerializer.INSTANCE);
  }

  @Test
  public void shouldSerializeAndDeserializeBack() {
    ObjectId id = ObjectId.fromString("1234567890123456789012345678901234567890");
    KeyWrapperMarshaller<ObjectId> marshaller = new KeyWrapperMarshaller<>(TEST_CACHE_NAME);

    final KeyWrapper<ObjectId> wrapped = new KeyWrapper<>(id);

    Bytes<ByteBuffer> out = Bytes.elasticByteBuffer();
    marshaller.write(out, wrapped);
    final KeyWrapper<ObjectId> actual = marshaller.read(out, null);
    assertThat(actual).isEqualTo(wrapped);
  }
}

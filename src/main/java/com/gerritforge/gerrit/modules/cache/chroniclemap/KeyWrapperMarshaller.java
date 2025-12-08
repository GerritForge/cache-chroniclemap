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

import com.google.gerrit.server.cache.serialize.CacheSerializer;
import com.googlesource.gerrit.modules.cache.chroniclemap.KeyWrapper;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.util.ReadResolvable;
import net.openhft.chronicle.hash.serialization.BytesReader;
import net.openhft.chronicle.hash.serialization.BytesWriter;

public class KeyWrapperMarshaller<V>
    implements BytesWriter<KeyWrapper<V>>,
        BytesReader<KeyWrapper<V>>,
        ReadResolvable<KeyWrapperMarshaller<V>> {

  private final String name;
  private final CacheSerializer<V> cacheSerializer;

  KeyWrapperMarshaller(String name) {
    this.name = name;
    this.cacheSerializer = CacheSerializers.getKeySerializer(name);
  }

  @Override
  public KeyWrapperMarshaller<V> readResolve() {
    return new KeyWrapperMarshaller<>(name);
  }

  @SuppressWarnings("rawtypes")
  @Override
  public KeyWrapper<V> read(Bytes in, KeyWrapper<V> using) {
    int serializedLength = (int) in.readUnsignedInt();
    byte[] serialized = new byte[serializedLength];
    in.read(serialized, 0, serializedLength);
    V v = cacheSerializer.deserialize(serialized);
    using = new KeyWrapper<>(v);

    return using;
  }

  @SuppressWarnings("rawtypes")
  @Override
  public void write(Bytes out, KeyWrapper<V> toWrite) {
    final byte[] serialized = cacheSerializer.serialize(toWrite.getValue());
    out.writeUnsignedInt(serialized.length);
    out.write(serialized);
  }
}

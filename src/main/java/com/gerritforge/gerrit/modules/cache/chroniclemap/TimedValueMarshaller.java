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

import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer0;
import com.google.gerrit.server.cache.serialize.CacheSerializer;
import java.nio.ByteBuffer;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.util.ReadResolvable;
import net.openhft.chronicle.hash.serialization.BytesReader;
import net.openhft.chronicle.hash.serialization.BytesWriter;

public class TimedValueMarshaller<V> extends SerializationMetricsForCache
    implements BytesWriter<TimedValue<V>>,
        BytesReader<TimedValue<V>>,
        ReadResolvable<TimedValueMarshaller<V>> {

  private static final ThreadLocal<byte[]> staticBuffer =
      new ThreadLocal<>() {
        @Override
        protected byte[] initialValue() {
          return new byte[Long.BYTES + Integer.BYTES];
        }
      };

  private final CacheSerializer<V> cacheSerializer;

  TimedValueMarshaller(MetricMaker metricMaker, String name) {
    super(metricMaker, name);
    this.cacheSerializer = CacheSerializers.getValueSerializer(name);
  }

  private TimedValueMarshaller(Metrics metrics, String name) {
    super(metrics, name);
    this.cacheSerializer = CacheSerializers.getValueSerializer(name);
  }

  @Override
  public TimedValueMarshaller<V> readResolve() {
    return new TimedValueMarshaller<>(metrics, name);
  }

  @SuppressWarnings("rawtypes")
  @Override
  public TimedValue<V> read(Bytes in, TimedValue<V> using) {
    try (Timer0.Context timer = metrics.deserializeLatency.start()) {
      byte[] bytesBuffer = staticBuffer.get();
      in.read(bytesBuffer);
      ByteBuffer buffer = ByteBuffer.wrap(bytesBuffer);
      long created = buffer.getLong();
      int vLength = buffer.getInt();

      // Deserialize object V (remaining bytes)
      byte[] serializedV = new byte[vLength];
      in.read(serializedV);
      V v = cacheSerializer.deserialize(serializedV);

      if (using == null) {
        using = new TimedValue<>(v, created);
      } else {
        using.setCreated(created);
        using.setValue(v);
      }
      return using;
    }
  }

  @SuppressWarnings("rawtypes")
  @Override
  public void write(Bytes out, TimedValue<V> toWrite) {
    try (Timer0.Context timer = metrics.serializeLatency.start()) {
      byte[] serialized = cacheSerializer.serialize(toWrite.getValue());

      // Serialize as follows:
      // created | length of serialized V | serialized value V
      // 8 bytes |       4 bytes          | serialized_length bytes
      byte[] bytesBuffer = staticBuffer.get();
      ByteBuffer buffer = ByteBuffer.wrap(bytesBuffer);
      buffer.putLong(toWrite.getCreated());
      buffer.putInt(serialized.length);
      out.write(bytesBuffer);
      out.write(serialized);
    }
  }
}

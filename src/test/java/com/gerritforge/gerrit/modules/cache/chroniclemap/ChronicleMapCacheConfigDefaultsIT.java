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

import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.gerritforge.gerrit.modules.cache.chroniclemap.ChronicleMapCacheConfig.Defaults;
import java.util.Set;
import org.junit.Test;

@UseLocalDisk
@UseSsh
public class ChronicleMapCacheConfigDefaultsIT extends AbstractDaemonTest {
  @Override
  public ChronicleMapCacheModule createModule() {
    // CacheSerializers is accumulating cache names from different test executions in CI therefore
    // it has to be cleared before this test
    CacheSerializers.clear();
    return new ChronicleMapCacheModule();
  }

  @Test
  // the following caches are not persisted by default hence `diskLimit` needs to be set so that
  // Gerrit persists them
  @GerritConfig(name = "cache.change_notes.diskLimit", value = "1")
  @GerritConfig(name = "cache.external_ids_map.diskLimit", value = "1")
  public void shouldAllPersistentCachesHaveDefaultConfiguration() throws Exception {
    Set<String> allCaches = CacheSerializers.getSerializersNames();
    assertThat(Defaults.defaultMap.keySet()).containsExactlyElementsIn(allCaches);
  }
}

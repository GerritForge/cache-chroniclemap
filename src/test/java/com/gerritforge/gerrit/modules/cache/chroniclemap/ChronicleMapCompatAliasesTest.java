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

import net.openhft.chronicle.core.pool.ClassAliasPool;
import org.junit.Test;

public class ChronicleMapCompatAliasesTest {

  @Test
  public void shouldResolveAllHistoricalChronicleAliases() {
    ChronicleMapCompatAliases.ensureRegistered();

    assertAliasResolvesTo(
        "com.googlesource.gerrit.modules.cache.chroniclemap.KeyWrapper", KeyWrapper.class);

    assertAliasResolvesTo(
        "com.googlesource.gerrit.modules.cache.chroniclemap.TimedValue", TimedValue.class);

    assertAliasResolvesTo(
        "com.googlesource.gerrit.modules.cache.chroniclemap.KeyWrapperMarshaller",
        KeyWrapperMarshaller.class);

    assertAliasResolvesTo(
        "com.googlesource.gerrit.modules.cache.chroniclemap.TimedValueMarshaller",
        TimedValueMarshaller.class);
  }

  private static void assertAliasResolvesTo(String alias, Class<?> expected) {
    Class<?> resolved = ClassAliasPool.CLASS_ALIASES.forName(alias);
    assertThat(resolved).isEqualTo(expected);
  }
}

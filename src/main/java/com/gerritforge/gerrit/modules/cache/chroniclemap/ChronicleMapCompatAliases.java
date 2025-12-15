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

import net.openhft.chronicle.core.pool.ClassAliasPool;

/**
 * Registers Chronicle Wire class aliases required to maintain backward compatibility for persisted
 * ChronicleMap caches.
 *
 * <p>ChronicleMap stores the fully-qualified class names of keys, values and their marshallers in
 * the cache file header. If those classes are later moved, renamed, or otherwise refactored,
 * existing cache files may fail to load because the original class names can no longer be resolved.
 *
 * <p>This class bridges that gap by registering aliases that map previously persisted class names
 * to their current implementations, allowing ChronicleMap to deserialize and reuse existing cache
 * data across refactorings and package namespace changes.
 *
 * <p>This method must be invoked before opening or recovering any persisted ChronicleMap. It is
 * safe to call multiple times.
 */
public final class ChronicleMapCompatAliases {
  private static boolean done;

  private ChronicleMapCompatAliases() {}

  public static synchronized void ensureRegistered() {
    if (done) {
      return;
    }

    ClassAliasPool.CLASS_ALIASES.addAlias(
        KeyWrapper.class, "com.googlesource.gerrit.modules.cache.chroniclemap.KeyWrapper");
    ClassAliasPool.CLASS_ALIASES.addAlias(
        TimedValue.class, "com.googlesource.gerrit.modules.cache.chroniclemap.TimedValue");
    ClassAliasPool.CLASS_ALIASES.addAlias(
        KeyWrapperMarshaller.class,
        "com.googlesource.gerrit.modules.cache.chroniclemap.KeyWrapperMarshaller");
    ClassAliasPool.CLASS_ALIASES.addAlias(
        TimedValueMarshaller.class,
        "com.googlesource.gerrit.modules.cache.chroniclemap.TimedValueMarshaller");

    done = true;
  }
}

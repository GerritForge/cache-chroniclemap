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

import com.google.auto.value.AutoValue;
import com.gerritforge.gerrit.modules.cache.chroniclemap.AutoValue_DefaultConfig;

@AutoValue
abstract class DefaultConfig {
  public static DefaultConfig create(
      long averageKey, long averageValue, long entries, int maxBloatFactor) {
    return new AutoValue_DefaultConfig(averageKey, averageValue, entries, maxBloatFactor);
  }

  abstract long averageKey();

  abstract long averageValue();

  abstract long entries();

  abstract int maxBloatFactor();
}

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

import com.google.common.base.Objects;

public class KeyWrapper<V> {

  private final V value;

  KeyWrapper(V value) {
    this.value = value;
  }

  public V getValue() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof KeyWrapper)) return false;
    KeyWrapper<?> that = (KeyWrapper<?>) o;
    return Objects.equal(value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(value);
  }
}

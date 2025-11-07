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
import static com.gerritforge.gerrit.modules.cache.chroniclemap.CacheNameSanitizer.sanitize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.metrics.MetricMaker;
import org.junit.Test;

public class CacheNameSanitizerTest {
  @Test
  public void shouldNotSanitizeTypicalCacheName() {
    String cacheName = "diff_summary";

    assertThat(sanitize(mock(MetricMaker.class), cacheName)).isEqualTo(cacheName);
  }

  @Test
  public void shouldNotSanitizeCacheNameWithHyphens() {
    String cacheName = "cache_name-with-hyphens";

    assertThat(sanitize(mock(MetricMaker.class), cacheName)).isEqualTo(cacheName);
  }

  @Test
  public void shouldFallbackToMetricMakerSanitization() {
    MetricMaker metricMaker = mock(MetricMaker.class);
    String sanitizedName = "sanitized";
    when(metricMaker.sanitizeMetricName(anyString())).thenReturn(sanitizedName);

    assertThat(sanitize(metricMaker, "very+confusing.cache#name")).isEqualTo(sanitizedName);
  }
}

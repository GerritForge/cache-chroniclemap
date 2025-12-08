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
import java.util.regex.Pattern;

public class CacheNameSanitizer {
  private static final Pattern METRIC_NAME_PATTERN =
      Pattern.compile("[a-zA-Z0-9_-]+([a-zA-Z0-9_-]+)*");

  /**
   * Detect if <code>cacheName</code> contains only metric name allowed characters (that matches
   * {@link #METRIC_NAME_PATTERN}). Note that `/` regardless of being allowed in metric names is
   * omitted as it denotes the sub-metric and cache name should not be treated as metric /
   * sub-metric. Typical persistent cache name (e.g. `diff_summary`) adheres to it therefore it is
   * returned without any modification. In all other cases call to {@link
   * MetricMaker#sanitizeMetricName(String)} is performed so that name is sanitized. This way
   * sanitization stays backward compatible but also non-typical cases are handled.
   */
  public static String sanitize(MetricMaker metricMaker, String cacheName) {
    if (METRIC_NAME_PATTERN.matcher(cacheName).matches()) {
      return cacheName;
    }
    return metricMaker.sanitizeMetricName(cacheName);
  }

  private CacheNameSanitizer() {}
}

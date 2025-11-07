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

import com.google.gerrit.extensions.registration.RegistrationHandle;
import com.google.gerrit.metrics.Counter0;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.MetricMaker;
import java.util.HashSet;
import java.util.Set;

class ChronicleMapStoreMetrics {
  private final String sanitizedName;
  private final MetricMaker metricMaker;
  private final String name;
  private final Counter0 storePutFailures;
  private final Set<RegistrationHandle> callbacks;

  ChronicleMapStoreMetrics(String name, MetricMaker metricMaker) {
    this.name = name;
    this.sanitizedName = CacheNameSanitizer.sanitize(metricMaker, name);
    this.metricMaker = metricMaker;

    this.storePutFailures =
        metricMaker.newCounter(
            "cache/chroniclemap/store_put_failures_" + sanitizedName,
            new Description(
                    "The number of errors caught when inserting entries in chronicle-map store: "
                        + name)
                .setCumulative()
                .setUnit("errors"));
    this.callbacks = new HashSet<>(3, 1.0F);
  }

  void incrementPutFailures() {
    storePutFailures.increment();
  }

  <K, V> void registerCallBackMetrics(ChronicleMapStore<K, V> store) {
    String PERCENTAGE_FREE_SPACE_METRIC =
        "cache/chroniclemap/percentage_free_space_" + sanitizedName;
    String REMAINING_AUTORESIZES_METRIC =
        "cache/chroniclemap/remaining_autoresizes_" + sanitizedName;
    String MAX_AUTORESIZES_METRIC = "cache/chroniclemap/max_autoresizes_" + sanitizedName;

    callbacks.add(
        metricMaker.newCallbackMetric(
            PERCENTAGE_FREE_SPACE_METRIC,
            Long.class,
            new Description(
                String.format("The amount of free space in the %s cache as a percentage", name)),
            () -> (long) store.percentageFreeSpace()));

    callbacks.add(
        metricMaker.newCallbackMetric(
            REMAINING_AUTORESIZES_METRIC,
            Integer.class,
            new Description(
                String.format(
                    "The number of times the %s cache can automatically expand its capacity",
                    name)),
            store::remainingAutoResizes));

    callbacks.add(
        metricMaker.newConstantMetric(
            MAX_AUTORESIZES_METRIC,
            store.maxAutoResizes(),
            new Description(
                String.format(
                    "The maximum number of times the %s cache can automatically expand its"
                        + " capacity",
                    name))));
  }

  void close() {
    storePutFailures.remove();
    callbacks.forEach(RegistrationHandle::remove);
    callbacks.clear();
  }
}

/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.context.extended;

import org.terracotta.context.ContextManager;
import org.terracotta.context.TreeNode;
import org.terracotta.context.query.Matcher;
import org.terracotta.context.query.Matchers;
import org.terracotta.context.query.Query;
import org.terracotta.statistics.OperationStatistic;
import org.terracotta.statistics.Time;
import org.terracotta.statistics.extended.CompoundOperation;
import org.terracotta.statistics.extended.CompoundOperationImpl;
import org.terracotta.statistics.extended.CountOperation;
import org.terracotta.statistics.extended.NullCompoundOperation;
import org.terracotta.statistics.extended.Result;
import org.terracotta.statistics.extended.SampledStatistic;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.terracotta.context.query.Matchers.attributes;
import static org.terracotta.context.query.Matchers.context;
import static org.terracotta.context.query.Matchers.hasAttribute;
import static org.terracotta.context.query.Matchers.identifier;
import static org.terracotta.context.query.Matchers.subclassOf;
import static org.terracotta.context.query.QueryBuilder.queryBuilder;

/**
 * @author Ludovic Orban
 */
public class StatisticsRegistry {

  private final Class<? extends OperationType> operationTypeClazz;
  private final Object contextObject;
  private final ConcurrentMap<OperationType, CompoundOperation<?>> standardOperations = new ConcurrentHashMap<OperationType, CompoundOperation<?>>();

  private final ScheduledExecutorService executor;
  private final Runnable disableTask;
  private volatile long timeToDisable;
  private volatile TimeUnit timeToDisableUnit;
  private volatile ScheduledFuture<?> disableStatus;

  private final long averageWindowDuration;
  private final TimeUnit averageWindowUnit;
  private final int historySize;
  private final long historyInterval;
  private final TimeUnit historyIntervalUnit;
  private final List<ExposedStatistic> registrations = new CopyOnWriteArrayList<ExposedStatistic>();

  public StatisticsRegistry(Class<? extends OperationType> operationTypeClazz, Object contextObject, ScheduledExecutorService executor, long averageWindowDuration,
                            TimeUnit averageWindowUnit, int historySize, long historyInterval, TimeUnit historyIntervalUnit, long timeToDisable, TimeUnit timeToDisableUnit) {
    if (!operationTypeClazz.isEnum()) {
      throw new IllegalArgumentException("StatisticsRegistry operationTypeClazz must be enum");
    }
    this.operationTypeClazz = operationTypeClazz;
    this.contextObject = contextObject;
    this.averageWindowDuration = averageWindowDuration;
    this.averageWindowUnit = averageWindowUnit;
    this.historySize = historySize;
    this.historyInterval = historyInterval;
    this.historyIntervalUnit = historyIntervalUnit;

    this.executor = executor;
    this.timeToDisable = timeToDisable;
    this.timeToDisableUnit = timeToDisableUnit;
    this.disableTask = createDisableTask();
    this.disableStatus = this.executor.scheduleAtFixedRate(disableTask, timeToDisable,
        timeToDisable, timeToDisableUnit);

    discoverOperationObservers();
  }

  private Runnable createDisableTask() {
    return new Runnable() {
      @Override
      public void run() {
        long expireThreshold = Time.absoluteTime() - timeToDisableUnit.toMillis(timeToDisable);
        for (CompoundOperation<?> o : standardOperations.values()) {
          if (o instanceof CompoundOperationImpl<?>) {
            ((CompoundOperationImpl<?>) o).expire(expireThreshold);
          }
        }
      }
    };
  }

  public synchronized void setTimeToDisable(long time, TimeUnit unit) {
    timeToDisable = time;
    timeToDisableUnit = unit;
    if (disableStatus != null) {
      disableStatus.cancel(false);
      disableStatus = executor.scheduleAtFixedRate(disableTask, timeToDisable, timeToDisable,
          timeToDisableUnit);
    }
  }

  public synchronized void setAlwaysOn(boolean enabled) {
    if (enabled) {
      if (disableStatus != null) {
        disableStatus.cancel(false);
        disableStatus = null;
      }
      for (CompoundOperation<?> o : standardOperations.values()) {
        o.setAlwaysOn(true);
      }
    } else {
      if (disableStatus == null) {
        disableStatus = executor.scheduleAtFixedRate(disableTask, 0, timeToDisable,
            timeToDisableUnit);
      }
      for (CompoundOperation<?> o : standardOperations.values()) {
        o.setAlwaysOn(false);
      }
    }
  }

  public void registerCompoundOperation(String name, Set<String> tags, Map<String, Object> properties, OperationType operationType, Set<?> operations) {
    Result result = getCompoundOperation(operationType).compound((Set) operations);
    ExposedStatistic exposedStatistic = new ExposedStatistic(name, operationType.type(), tags, properties, result);
    registrations.add(exposedStatistic);
  }

  public void registerCountOperation(String name, Set<String> tags, Map<String, Object> properties, OperationType operationType) {
    CountOperation<? extends Enum<?>> countOperation = getCompoundOperation(operationType).asCountOperation();
    ExposedStatistic exposedStatistic = new ExposedStatistic(name, operationType.type(), tags, properties, countOperation);
    registrations.add(exposedStatistic);
  }

  public void registerRatio(String name, Set<String> tags, Map<String, Object> properties, OperationType operationType, Set<?> numerator, Set<?> denominator) {
    SampledStatistic ratio = getCompoundOperation(operationType).ratioOf((Set) numerator, (Set) denominator);
    ExposedStatistic exposedStatistic = new ExposedStatistic(name, operationType.type(), tags, properties, ratio);
    registrations.add(exposedStatistic);
  }

  public Collection<ExposedStatistic> getRegistrations() {
    return Collections.unmodifiableCollection(registrations);
  }

  public void clearRegistrations() {
    registrations.clear();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private CompoundOperation<?> getCompoundOperation(OperationType operationType) {
    CompoundOperation<?> operation = standardOperations.get(operationType);
    if (operation instanceof NullCompoundOperation<?>) {
      OperationStatistic<?> discovered = findOperationObserver(operationType);
      if (discovered == null) {
        return operation;
      } else {
        CompoundOperation<?> newOperation = new CompoundOperationImpl(discovered, operationType.type(),
            averageWindowDuration, averageWindowUnit, executor, historySize,
            historyInterval, historyIntervalUnit);
        if (standardOperations.replace(operationType, operation, newOperation)) {
          return newOperation;
        } else {
          return standardOperations.get(operationType);
        }
      }
    } else {
      return operation;
    }
  }


  @SuppressWarnings({"unchecked", "rawtypes"})
  private void discoverOperationObservers() {
    for (OperationType t : operationTypeClazz.getEnumConstants()) {
      OperationStatistic statistic = findOperationObserver(t);
      if (statistic == null) {
        if (t.required()) {
          throw new IllegalStateException("Required statistic " + t + " not found");
        } else {
          Class type = t.type();
          CompoundOperation compoundOperation = NullCompoundOperation.instance(type);
          standardOperations.put(t, compoundOperation);
        }
      } else {
        CompoundOperationImpl compoundOperation = new CompoundOperationImpl(statistic, t.type(),
            averageWindowDuration, averageWindowUnit, executor, historySize,
            historyInterval, historyIntervalUnit);
        standardOperations.put(t, compoundOperation);
      }
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private OperationStatistic findOperationObserver(OperationType statistic) {
    Set<OperationStatistic<?>> results = findOperationObserver(statistic.context(), statistic.type(), statistic.operationName(), statistic.tags());
    switch (results.size()) {
      case 0:
        return null;
      case 1:
        return (OperationStatistic) results.iterator().next();
      default:
        throw new IllegalStateException("Duplicate statistics found for " + statistic);
    }
  }

  @SuppressWarnings("unchecked")
  private Set<OperationStatistic<?>> findOperationObserver(Query contextQuery, Class<?> type, String name,
                                                           final Set<String> tags) {
    Query q = queryBuilder().chain(contextQuery)
        .children().filter(context(identifier(subclassOf(OperationStatistic.class)))).build();

    Set<TreeNode> operationStatisticNodes = q.execute(Collections.singleton(ContextManager.nodeFor(contextObject)));
    Set<TreeNode> result = queryBuilder()
        .filter(
            context(attributes(Matchers.<Map<String, Object>>allOf(hasAttribute("type", type),
                hasAttribute("name", name), hasAttribute("tags", new Matcher<Set<String>>() {
                  @Override
                  protected boolean matchesSafely(Set<String> object) {
                    return object.containsAll(tags);
                  }
                }))))).build().execute(operationStatisticNodes);

    if (result.isEmpty()) {
      return Collections.emptySet();
    } else {
      Set<OperationStatistic<?>> statistics = new HashSet<OperationStatistic<?>>();
      for (TreeNode node : result) {
        statistics.add((OperationStatistic<?>) node.getContext().attributes().get("this"));
      }
      return statistics;
    }
  }

}

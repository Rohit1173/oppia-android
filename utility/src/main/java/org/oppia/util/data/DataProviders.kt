package org.oppia.util.data

import androidx.annotation.GuardedBy
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oppia.util.threading.BackgroundDispatcher
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Various functions to create or manipulate [DataProvider]s.
 *
 * It's recommended to transform providers rather than [LiveData] since the latter occurs on the main thread, and the
 * former can occur safely on background threads to reduce UI lag and user perceived latency.
 */
@Singleton
class DataProviders @Inject constructor(
  @BackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
  private val asyncDataSubscriptionManager: AsyncDataSubscriptionManager
) {
  /**
   * Returns a new [DataProvider] that applies the specified function each time new data is available to it, and
   * provides it to its own subscribers.
   *
   * Notifications to the original data provider will also notify subscribers to the transformed data provider of
   * changes, but not vice versa.
   *
   * Note that the input transformation function should be non-blocking, have no side effects, and be thread-safe since
   * it may be called on different background threads at different times. It should perform no UI operations or
   * otherwise interact with UI components.
   */
  fun <T1, T2> transform(newId: Any, dataProvider: DataProvider<T1>, function: (T1) -> T2): DataProvider<T2> {
    asyncDataSubscriptionManager.associateIds(newId, dataProvider.getId())
    return object: DataProvider<T2> {
      override fun getId(): Any {
        return newId
      }

      override suspend fun retrieveData(): AsyncResult<T2> {
        return try {
          dataProvider.retrieveData().transform(function)
        } catch (t: Throwable) {
          AsyncResult.failed(t)
        }
      }
    }
  }

  /**
   * Returns a transformed [DataProvider] in the same way as [transform] except the transformation function can be
   * blocking.
   */
  fun <T1, T2> transformAsync(
    newId: Any, dataProvider: DataProvider<T1>, function: suspend (T1) -> AsyncResult<T2>
  ): DataProvider<T2> {
    asyncDataSubscriptionManager.associateIds(newId, dataProvider.getId())
    return object: DataProvider<T2> {
      override fun getId(): Any {
        return newId
      }

      override suspend fun retrieveData(): AsyncResult<T2> {
        return dataProvider.retrieveData().transformAsync(function)
      }
    }
  }

  /**
   * Returns a new [DataProvider] that combines two other providers by applying the specified function to produce a new
   * value each time either data provider changes.
   *
   * Notifications to the original data providers will also notify subscribers to the combined data provider of
   * changes, but not vice versa.
   *
   * Note that the combine function should be non-blocking, have no side effects, and be thread-safe since it may be
   * called on different background threads at different times. It should perform no UI operations or otherwise interact
   * with UI components.
   */
  fun <O, T1, T2> combine(
    newId: Any, dataProvider1: DataProvider<T1>, dataProvider2: DataProvider<T2>, function: (T1, T2) -> O
  ): DataProvider<O> {
    asyncDataSubscriptionManager.associateIds(newId, dataProvider1.getId())
    asyncDataSubscriptionManager.associateIds(newId, dataProvider2.getId())
    return object: DataProvider<O> {
      override fun getId(): Any {
        return newId
      }

      override suspend fun retrieveData(): AsyncResult<O> {
        return try {
          dataProvider1.retrieveData().combineWith(dataProvider2.retrieveData(), function)
        } catch (t: Throwable) {
          AsyncResult.failed(t)
        }
      }
    }
  }

  /**
   * Returns a transformed [DataProvider] in the same way as [combine] except the combine function can be blocking.
   */
  fun <O, T1, T2> combineAsync(
    newId: Any, dataProvider1: DataProvider<T1>, dataProvider2: DataProvider<T2>,
    function: suspend (T1, T2) -> AsyncResult<O>
  ): DataProvider<O> {
    asyncDataSubscriptionManager.associateIds(newId, dataProvider1.getId())
    asyncDataSubscriptionManager.associateIds(newId, dataProvider2.getId())
    return object: DataProvider<O> {
      override fun getId(): Any {
        return newId
      }

      override suspend fun retrieveData(): AsyncResult<O> {
        return dataProvider1.retrieveData().combineWithAsync(dataProvider2.retrieveData(), function)
      }
    }
  }

  /**
   * Returns a new in-memory [DataProvider] with the specified function being called each time the provider's data is
   * retrieved, and the specified identifier.
   *
   * Note that the loadFromMemory function should be non-blocking, and have no side effects. It should also be thread
   * safe since it can be called from different background threads. It also should never interact with UI components or
   * perform UI operations.
   *
   * Changes to the returned data provider can be propagated using calls to [AsyncDataSubscriptionManager.notifyChange]
   * with the in-memory provider's identifier.
   */
  fun <T> createInMemoryDataProvider(id: Any, loadFromMemory: () -> T): DataProvider<T> {
    return object: DataProvider<T> {
      override fun getId(): Any {
        return id
      }

      override suspend fun retrieveData(): AsyncResult<T> {
        return try {
          AsyncResult.success(loadFromMemory())
        } catch (t: Throwable) {
          AsyncResult.failed(t)
        }
      }
    }
  }

  /**
   * Returns a new in-memory [DataProvider] in the same way as [createInMemoryDataProvider] except the load function can
   * be blocking.
   */
  fun <T> createInMemoryDataProviderAsync(id: Any, loadFromMemoryAsync: suspend () -> AsyncResult<T>): DataProvider<T> {
    return object: DataProvider<T> {
      override fun getId(): Any {
        return id
      }

      override suspend fun retrieveData(): AsyncResult<T> {
        return loadFromMemoryAsync()
      }
    }
  }

  /**
   * Converts a [DataProvider] to [LiveData]. This will use a background executor to handle processing of the coroutine,
   * but [LiveData] guarantees that final delivery of the result will happen on the main thread.
   */
  fun <T> convertToLiveData(dataProvider: DataProvider<T>): LiveData<AsyncResult<T>> {
    return NotifiableAsyncLiveData(backgroundDispatcher, asyncDataSubscriptionManager, dataProvider)
  }

  /**
   * A version of [LiveData] which automatically pipes data from a specified [DataProvider] to LiveData observers in a
   * thread-safe and lifecycle-safe way.
   *
   * This class will immediately retrieve the latest state of its input [DataProvider] at the first occurrence of an
   * observer, but not before then. It guarantees that all active observers (including new ones) will receive an
   * eventually consistent state of the data provider. It also will not deliver the same value more than once in a row
   * to avoid over-alerting observers of changes.
   */
  private class NotifiableAsyncLiveData<T>(
    private val dispatcher: CoroutineDispatcher,
    private val asyncDataSubscriptionManager: AsyncDataSubscriptionManager,
    private val dataProvider: DataProvider<T>
  ): LiveData<AsyncResult<T>>() {
    private val asyncSubscriber: ObserveAsyncChange = this::handleDataProviderUpdate
    private val isActive = AtomicBoolean(false)
    private val runningJob = AtomicReference<Job?>(null)
    private var cache: AsyncResult<T>? = null // only accessed on the main thread

    override fun onActive() {
      super.onActive()
      // Subscribe the ID immediately in case there's a value in the data provider already ready.
      asyncDataSubscriptionManager.subscribe(dataProvider.getId(), asyncSubscriber)
      isActive.set(true)

      // If there's no currently cached value or soon-to-be cached value, kick-off a data retrieval so that new
      // observers can receive the most up-to-date value.
      if (runningJob.get() == null) {
        val scope = CoroutineScope(dispatcher)
        val job = scope.launch {
          handleDataProviderUpdate()
        }
        // Note that this can race against handleDataProviderUpdate() clearing the job, but in either outcome the
        // behavior should still be correct via eventual consistency.
        runningJob.set(job)
      }
    }

    override fun onInactive() {
      super.onInactive()
      // Stop watching for updates immediately, then cancel any existing operations.
      asyncDataSubscriptionManager.unsubscribe(dataProvider.getId(), asyncSubscriber)
      isActive.set(false)
      // This can cancel downstream operations that may want to complete side effects.
      runningJob.getAndSet(null)?.cancel()
    }

    override fun setValue(value: AsyncResult<T>?) {
      // TODO(BenHenning): Fetch the in-memory cache from the data provider (once possible) to get the latest snapshot
      // rather than incorrectly ignoring the value being passed to setValue().
//      val (propagateValue, newValue) = coroutineLiveDataLock.withLock {
//        val isNewValue = cache != pendingCache

        // Indicate that this live data now has a cached value.
//        cache = pendingCache

//        return@withLock Pair(isNewValue, pendingCache)
//      }

      // Only propagate the value if it's changed.
//      if (propagateValue) {
//        super.setValue(newValue)
//      }

      checkNotNull(value) { "Null values should not be posted to coroutine LiveDatas." }
      val currentCache = cache // This is safe because cache can only be changed on the main thread.
      if (currentCache != null) {
        if (value.isNewerThanOrSameAgeAs(currentCache) && currentCache != value) {
          // Only propagate the value if it's changed and is newer since it's possible for subscription callbacks to
          // happen out-of-order.
          cache = value
          super.setValue(value)
        }
      } else {
        cache = value
        super.setValue(value)
      }
    }

    private suspend fun handleDataProviderUpdate() {
      // This doesn't guarantee that retrieveData() is only called when the live data is active (e.g. it can become
      // inactive right after the value is posted & before it's dispatched), but it does guarantee that it won't be
      // called when the live data is currently inactive.
//      if (isActive.get()) {
//        coroutineLiveDataLock.withLock {
          // Save the latest call to retrieveData() as the source of truth in case calls into retrieveData() trigger a
          // loop (e.g. due to a deferred quickly executing and notifying provider changes) where the loop may be executed
          // out of order (though this is generally expected to only happen in tests due to differing dispatcher behavior
          // from prod).
//          val newPendingValue = dataProvider.retrieveData()
//          pendingCache = newPendingValue
//          super.postValue(newPendingValue)
//          runningJob.set(null) // Erase any pending jobs since the live data will soon be up-to-date.
//        }
//      }

      fetchFromDataProvider()?.let {
        super.postValue(it)
        runningJob.set(null)
      }
    }

    private suspend fun fetchFromDataProvider(): AsyncResult<T>? {
      return if (isActive.get()) {
        // Although it's possible for the live data to become inactive after this point, this follows the expected
        // contract of the data provider (it may have its data fetched and not delivered), and it guarantees eventual
        // consistency since the class still caches the results in case a new observer is added later.
        dataProvider.retrieveData()
      } else null
    }
  }
}

package datadog.trace.agent.tooling;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.googlecode.concurrentlinkedhashmap.EvictionListener;
import datadog.trace.api.Function;
import datadog.trace.bootstrap.WeakCache;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

public class CLHMWeakCache<K, V> extends ReferenceQueue<K> implements WeakCache<K, V> {
  public static final class Provider implements WeakCache.Provider {
    @Override
    public <K, V> WeakCache<K, V> newWeakCache(long maxSize) {
      return new CLHMWeakCache<>(maxSize);
    }
  }

  private static final int CACHE_CONCURRENCY =
      Math.max(8, Runtime.getRuntime().availableProcessors());
  private final ConcurrentLinkedHashMap<Object, V> concurrentMap;

  public CLHMWeakCache(long maxSize) {
    concurrentMap =
        new ConcurrentLinkedHashMap.Builder<Object, V>()
            .maximumWeightedCapacity(maxSize)
            .listener(
                new EvictionListener<Object, V>() {
                  @Override
                  public void onEviction(Object key, Object value) {
                    CLHMWeakCache.this.expungeStaleEntries();
                  }
                })
            .concurrencyLevel(CACHE_CONCURRENCY)
            .build();
  }

  @Override
  public V getIfPresent(K key) {
    return concurrentMap.get(key);
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    V value = getIfPresent(key);
    if (value == null) {
      value = mappingFunction.apply(key);
      V oldValue = concurrentMap.putIfAbsent(new WeakKey<>(key, this), value);
      if (oldValue != null) {
        value = oldValue;
      }
    }

    return value;
  }

  @Override
  public void put(K key, V value) {
    concurrentMap.put(new WeakKey<>(key, this), value);
  }

  private void expungeStaleEntries() {
    Reference<?> reference;
    while ((reference = poll()) != null) {
      concurrentMap.remove(reference);
    }
  }

  private static final class WeakKey<T> extends WeakReference<T> {

    private final int hashCode;

    WeakKey(T key, ReferenceQueue<? super T> queue) {
      super(key, queue);
      hashCode = key.hashCode();
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object other) {
      if (other == this) {
        return true;
      }
      if (null == other) {
        return false;
      }
      T key = get();
      if (null == key) {
        return false;
      }
      Object otherKey;
      if (other instanceof WeakKey) {
        WeakKey<?> otherWK = (WeakKey<?>) other;
        if (hashCode != otherWK.hashCode) {
          return false;
        }
        otherKey = otherWK.get();
        if (null == otherKey) {
          return false;
        }
      } else {
        otherKey = other;
      }
      return key.equals(otherKey);
    }

    @Override
    public String toString() {
      return String.valueOf(get());
    }
  }
}

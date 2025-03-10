package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.bytebuddy.DDCachingPoolStrategy;
import datadog.trace.agent.tooling.bytebuddy.DDLocationStrategy;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.WeakCache;
import datadog.trace.bootstrap.WeakCache.Provider;
import datadog.trace.bootstrap.WeakMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class contains class references for objects shared by the agent installer as well as muzzle
 * (both compile and runtime). Extracted out from AgentInstaller to begin separating some of the
 * logic out.
 */
public class AgentTooling {
  private static final Logger log = LoggerFactory.getLogger(AgentTooling.class);

  static {
    // WeakMap is used by other classes below, so we need to register the provider first.
    registerWeakMapProvider();
  }

  public static void registerWeakMapProvider() {
    WeakMap.Provider.registerIfAbsent(new WeakMapSuppliers.WeakConcurrent());
  }

  private static Provider loadWeakCacheProvider() {
    ClassLoader classLoader = AgentInstaller.class.getClassLoader();
    Class<Provider> providerClass;

    try {
      providerClass =
          (Class<Provider>)
              classLoader.loadClass("datadog.trace.agent.tooling.CLHMWeakCache$Provider");

      return providerClass.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Can't load implementation of WeakCache.Provider", e);
    }
  }

  private static final long DEFAULT_CACHE_CAPACITY = 32;
  private static final Provider weakCacheProvider = loadWeakCacheProvider();

  private static final DDLocationStrategy LOCATION_STRATEGY = new DDLocationStrategy();
  private static final DDCachingPoolStrategy POOL_STRATEGY =
      new DDCachingPoolStrategy(Config.get().isResolverUseLoadClassEnabled());

  public static <K, V> WeakCache<K, V> newWeakCache() {
    return newWeakCache(DEFAULT_CACHE_CAPACITY);
  }

  public static <K, V> WeakCache<K, V> newWeakCache(final long maxSize) {
    return weakCacheProvider.newWeakCache(maxSize);
  }

  public static DDLocationStrategy locationStrategy() {
    return LOCATION_STRATEGY;
  }

  public static DDCachingPoolStrategy poolStrategy() {
    return POOL_STRATEGY;
  }
}

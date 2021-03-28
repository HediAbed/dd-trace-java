package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.skipClassLoaderByName;

import datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnoresMatcher;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy;

/**
 * Selects specific classes loaded during agent installation that we want to re-transform.
 *
 * <p>This handles a situation where our ByteBuddy transformer won't be notified of "definitions of
 * classes upon which any registered transformer is dependent". These classes are already loaded so
 * we cannot make structural changes but we can still add method advice by re-transforming them.
 *
 * @see Instrumentation#addTransformer(ClassFileTransformer, boolean)
 */
public final class DDRediscoveryStrategy implements RedefinitionStrategy.DiscoveryStrategy {
  @Override
  public Iterable<Iterable<Class<?>>> resolve(final Instrumentation instrumentation) {
    return new Iterable<Iterable<Class<?>>>() {
      @Override
      public Iterator<Iterable<Class<?>>> iterator() {
        return new Iterator<Iterable<Class<?>>>() {
          private final Set<Class<?>> visited = new HashSet<>();
          private List<Class<?>> retransforming;

          @Override
          public boolean hasNext() {
            if (null == retransforming) {
              retransforming = new ArrayList<>();
              for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                ClassLoader classLoader = clazz.getClassLoader();
                if ((null == classLoader
                        ? shouldRetransformBootstrapClass(clazz.getName())
                        : !skipClassLoaderByName(classLoader))
                    && visited.add(clazz)) {
                  retransforming.add(clazz);
                }
              }
            }
            return !retransforming.isEmpty();
          }

          @Override
          public Iterable<Class<?>> next() {
            if (hasNext()) {
              try {
                return retransforming;
              } finally {
                retransforming = null;
              }
            }
            throw new NoSuchElementException();
          }

          @Override
          public void remove() {
            throw new UnsupportedOperationException();
          }

          /**
           * This can be viewed as the inverse of {@link GlobalIgnoresMatcher} - it only lists
           * bootstrap classes loaded during agent installation that we explicitly want to be
           * re-transformed.
           */
          public boolean shouldRetransformBootstrapClass(final String name) {
            switch (name) {
              case "java.lang.Throwable":
              case "java.net.HttpURLConnection":
              case "java.net.URL":
              case "sun.net.www.http.HttpClient":
              case "datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper":
                return true;
            }
            if (name.startsWith("java.util.concurrent.")
                || name.startsWith("sun.net.www.protocol.")
                || name.startsWith("java.rmi.")
                || name.startsWith("sun.rmi.server.")
                || name.startsWith("sun.rmi.transport.")) {
              return true;
            }
            if (name.startsWith("java.util.logging.")) {
              // Concurrent instrumentation modifies the structure of the Cleaner class incompatibly
              // with java9+ modules. Excluding it as a workaround until a long-term fix for modules
              // can be put in place.
              return !name.equals("java.util.logging.LogManager$Cleaner");
            }
            return false;
          }
        };
      }
    };
  }
}

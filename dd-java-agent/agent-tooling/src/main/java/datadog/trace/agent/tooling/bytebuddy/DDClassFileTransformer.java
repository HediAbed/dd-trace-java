package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.skipClassLoaderByName;

import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;

/** Intercepts transformation requests before ByteBuddy so we can perform some initial filtering. */
public final class DDClassFileTransformer extends ResettableClassFileTransformer.WithDelegation {

  public DDClassFileTransformer(final ResettableClassFileTransformer classFileTransformer) {
    super(classFileTransformer);
  }

  @Override
  public byte[] transform(
      final ClassLoader classLoader,
      final String internalClassName,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain,
      final byte[] classFileBuffer)
      throws IllegalClassFormatException {

    if (null != classLoader && skipClassLoaderByName(classLoader)) {
      return null;
    }

    return classFileTransformer.transform(
        classLoader, internalClassName, classBeingRedefined, protectionDomain, classFileBuffer);
  }
}

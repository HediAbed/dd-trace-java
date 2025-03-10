package datadog.trace.agent.tooling.muzzle;

import static datadog.trace.util.Strings.getClassName;
import static datadog.trace.util.Strings.getResourceName;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/** Visit a class and collect all references made by the visited class. */
// Additional things we could check
// - annotations on class
// - outer class
// - inner class
// - cast opcodes in method bodies
public class ReferenceCreator extends ClassVisitor {
  /**
   * Classes in this namespace will be scanned and used to create references.
   *
   * <p>For now we're hardcoding this to the instrumentation package so we only create references
   * from the method advice and helper classes.
   */
  private static final String REFERENCE_CREATION_PACKAGE = "datadog.trace.instrumentation.";

  private static final int UNDEFINED_LINE = -1;

  /**
   * Generate all references reachable from a given class.
   *
   * @param entryPointClassName Starting point for generating references.
   * @param loader Classloader used to read class bytes.
   * @return Map of [referenceClassName -> Reference]
   * @throws IllegalStateException if class is not found or unable to be loaded.
   */
  public static Map<String, Reference> createReferencesFrom(
      final String entryPointClassName, final ClassLoader loader) throws IllegalStateException {
    final Set<String> visitedSources = new HashSet<>();
    final Map<String, Reference> references = new LinkedHashMap<>();

    final Queue<String> instrumentationQueue = new ArrayDeque<>();
    instrumentationQueue.add(entryPointClassName);

    while (!instrumentationQueue.isEmpty()) {
      final String className = instrumentationQueue.remove();
      visitedSources.add(className);
      String resourceName = getResourceName(className);
      try (InputStream in = loader.getResourceAsStream(resourceName)) {
        if (null == in) {
          System.err.println(resourceName + " not found, skipping");
          continue;
        }
        final ReferenceCreator cv = new ReferenceCreator(null);
        final ClassReader reader = new ClassReader(in);
        reader.accept(cv, ClassReader.SKIP_FRAMES);

        final Map<String, Reference> instrumentationReferences = cv.getReferences();
        for (final Map.Entry<String, Reference> entry : instrumentationReferences.entrySet()) {
          // Don't generate references created outside of the datadog instrumentation package.
          if (!visitedSources.contains(entry.getKey())
              && entry.getKey().startsWith(REFERENCE_CREATION_PACKAGE)) {
            instrumentationQueue.add(entry.getKey());
          }
          Reference toMerge = references.get(entry.getKey());
          if (null == toMerge) {
            references.put(entry.getKey(), entry.getValue());
          } else {
            references.put(entry.getKey(), toMerge.merge(entry.getValue()));
          }
        }

      } catch (final IOException e) {
        throw new IllegalStateException("Error reading class " + className, e);
      }
    }
    return references;
  }

  private static boolean samePackage(String from, String to) {
    int fromLength = from.lastIndexOf('/');
    int toLength = to.lastIndexOf('/');
    return fromLength == toLength && from.regionMatches(0, to, 0, fromLength + 1);
  }

  /**
   * Compute the minimum required access for FROM class to access the TO class.
   *
   * @return A reference flag with the required level of access.
   */
  private static Reference.Flag computeMinimumClassAccess(final String from, final String to) {
    if (from.equalsIgnoreCase(to)) {
      return Reference.Flag.PRIVATE_OR_HIGHER;
    } else if (samePackage(from, to)) {
      return Reference.Flag.PACKAGE_OR_HIGHER;
    } else {
      return Reference.Flag.PUBLIC;
    }
  }

  /**
   * Compute the minimum required access for FROM class to access a field on the TO class.
   *
   * @return A reference flag with the required level of access.
   */
  private static Reference.Flag computeMinimumFieldAccess(final String from, final String to) {
    if (from.equalsIgnoreCase(to)) {
      return Reference.Flag.PRIVATE_OR_HIGHER;
    } else if (samePackage(from, to)) {
      return Reference.Flag.PACKAGE_OR_HIGHER;
    } else {
      // Additional references: check the type hierarchy of FROM to distinguish public from
      // protected
      return Reference.Flag.PROTECTED_OR_HIGHER;
    }
  }

  /**
   * Compute the minimum required access for FROM class to access METHODTYPE on the TO class.
   *
   * @return A reference flag with the required level of access.
   */
  private static Reference.Flag computeMinimumMethodAccess(final String from, final String to) {
    if (from.equalsIgnoreCase(to)) {
      return Reference.Flag.PRIVATE_OR_HIGHER;
    } else {
      // Additional references: check the type hierarchy of FROM to distinguish public from
      // protected
      return Reference.Flag.PROTECTED_OR_HIGHER;
    }
  }

  /**
   * @return If TYPE is an array, return the underlying type. If TYPE is not an array simply return
   *     the type.
   */
  private static Type underlyingType(Type type) {
    while (type.getSort() == Type.ARRAY) {
      type = type.getElementType();
    }
    return type;
  }

  private final Map<String, Reference> references = new LinkedHashMap<>();
  private String refSourceClassName;
  private String refSourceTypeInternalName;
  private Type refSourceType;

  private ReferenceCreator(final ClassVisitor classVisitor) {
    super(Opcodes.ASM7, classVisitor);
  }

  public Map<String, Reference> getReferences() {
    return references;
  }

  private void addReference(final Reference ref) {
    if (!ref.getClassName().startsWith("java.")) {
      Reference reference = references.get(ref.getClassName());
      if (null == reference) {
        references.put(ref.getClassName(), ref);
      } else {
        references.put(ref.getClassName(), reference.merge(ref));
      }
    }
  }

  @Override
  public void visit(
      final int version,
      final int access,
      final String name,
      final String signature,
      final String superName,
      final String[] interfaces) {
    refSourceClassName = getClassName(name);
    refSourceType = Type.getType("L" + name + ";");
    refSourceTypeInternalName = refSourceType.getInternalName();

    // Add references to each of the interfaces.
    for (String iface : interfaces) {
      addReference(
          new Reference.Builder(iface)
              .withSource(
                  refSourceClassName,
                  UNDEFINED_LINE) // We don't have a specific line number to use.
              .withFlag(Reference.Flag.PUBLIC)
              .build());
    }
    // the super type is handled by the method visitor to the constructor.
    super.visit(version, access, name, signature, superName, interfaces);
  }

  @Override
  public FieldVisitor visitField(
      final int access,
      final String name,
      final String descriptor,
      final String signature,
      final Object value) {
    // Additional references we could check
    // - annotations on field

    // intentionally not creating refs to fields here.
    // Will create refs in method instructions to include line numbers.
    return super.visitField(access, name, descriptor, signature, value);
  }

  @Override
  public MethodVisitor visitMethod(
      final int access,
      final String name,
      final String descriptor,
      final String signature,
      final String[] exceptions) {
    // Additional references we could check
    // - Classes in signature (return type, params) and visible from this package
    return new AdviceReferenceMethodVisitor(
        super.visitMethod(access, name, descriptor, signature, exceptions));
  }

  private class AdviceReferenceMethodVisitor extends MethodVisitor {
    private int currentLineNumber = UNDEFINED_LINE;

    public AdviceReferenceMethodVisitor(final MethodVisitor methodVisitor) {
      super(Opcodes.ASM7, methodVisitor);
    }

    @Override
    public void visitLineNumber(final int line, final Label start) {
      currentLineNumber = line;
      super.visitLineNumber(line, start);
    }

    @Override
    public void visitFieldInsn(
        final int opcode, final String owner, final String name, final String descriptor) {
      // Additional references we could check
      // * DONE owner class
      //   * DONE owner class has a field (name)
      //   * DONE field is static or non-static
      //   * DONE field's visibility from this point (NON_PRIVATE?)
      // * DONE owner class's visibility from this point (NON_PRIVATE?)
      //
      // * DONE field-source class (descriptor)
      //   * DONE field-source visibility from this point (PRIVATE?)

      final Type ownerType =
          owner.startsWith("[")
              ? underlyingType(Type.getType(owner))
              : Type.getType("L" + owner + ";");
      final Type fieldType = Type.getType(descriptor);

      String ownerTypeInternalName = ownerType.getInternalName();

      final List<Reference.Flag> fieldFlags = new ArrayList<>();
      fieldFlags.add(computeMinimumFieldAccess(refSourceTypeInternalName, ownerTypeInternalName));
      fieldFlags.add(
          opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC
              ? Reference.Flag.STATIC
              : Reference.Flag.NON_STATIC);

      addReference(
          new Reference.Builder(ownerTypeInternalName)
              .withSource(refSourceClassName, currentLineNumber)
              .withFlag(computeMinimumClassAccess(refSourceTypeInternalName, ownerTypeInternalName))
              .withField(
                  new Reference.Source[] {
                    new Reference.Source(refSourceClassName, currentLineNumber)
                  },
                  fieldFlags.toArray(new Reference.Flag[0]),
                  name,
                  fieldType)
              .build());

      final Type underlyingFieldType = underlyingType(fieldType);
      String underlyingFieldTypeInternalName = underlyingFieldType.getInternalName();
      if (underlyingFieldType.getSort() == Type.OBJECT) {
        addReference(
            new Reference.Builder(underlyingFieldTypeInternalName)
                .withSource(refSourceClassName, currentLineNumber)
                .withFlag(
                    computeMinimumClassAccess(
                        refSourceTypeInternalName, underlyingFieldTypeInternalName))
                .build());
      }
      super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    @Override
    public void visitMethodInsn(
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
      // Additional references we could check
      // * DONE name of method owner's class
      //   * DONE is the owner an interface?
      //   * DONE owner's access from here (PRIVATE?)
      //   * DONE method on the owner class
      //   * DONE is the method static? Is it visible from here?
      // * Class names from the method descriptor
      //   * params classes
      //   * return type
      final Type methodType = Type.getMethodType(descriptor);

      { // ref for method return type
        final Type returnType = underlyingType(methodType.getReturnType());
        String returnTypeInternalName = returnType.getInternalName();
        if (returnType.getSort() == Type.OBJECT) {
          addReference(
              new Reference.Builder(returnTypeInternalName)
                  .withSource(refSourceClassName, currentLineNumber)
                  .withFlag(
                      computeMinimumClassAccess(refSourceTypeInternalName, returnTypeInternalName))
                  .build());
        }
      }
      // refs for method param types
      for (Type paramType : methodType.getArgumentTypes()) {
        paramType = underlyingType(paramType);
        String paramTypeInternalName = paramType.getInternalName();
        if (paramType.getSort() == Type.OBJECT) {
          addReference(
              new Reference.Builder(paramTypeInternalName)
                  .withSource(refSourceClassName, currentLineNumber)
                  .withFlag(
                      computeMinimumClassAccess(refSourceTypeInternalName, paramTypeInternalName))
                  .build());
        }
      }

      final Type ownerType =
          owner.startsWith("[")
              ? underlyingType(Type.getType(owner))
              : Type.getType("L" + owner + ";");
      String ownerTypeInternalName = ownerType.getInternalName();

      final List<Reference.Flag> methodFlags = new ArrayList<>();
      methodFlags.add(
          opcode == Opcodes.INVOKESTATIC ? Reference.Flag.STATIC : Reference.Flag.NON_STATIC);
      methodFlags.add(computeMinimumMethodAccess(refSourceTypeInternalName, ownerTypeInternalName));

      addReference(
          new Reference.Builder(ownerTypeInternalName)
              .withSource(refSourceClassName, currentLineNumber)
              .withFlag(isInterface ? Reference.Flag.INTERFACE : Reference.Flag.NON_INTERFACE)
              .withFlag(computeMinimumClassAccess(refSourceTypeInternalName, ownerTypeInternalName))
              .withMethod(
                  new Reference.Source[] {
                    new Reference.Source(refSourceClassName, currentLineNumber)
                  },
                  methodFlags.toArray(new Reference.Flag[0]),
                  name,
                  methodType.getReturnType(),
                  methodType.getArgumentTypes())
              .build());
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitTypeInsn(final int opcode, final String type) {
      addReference(
          new Reference.Builder(type)
              .withSource(refSourceClassName, currentLineNumber)
              .withFlag(
                  computeMinimumClassAccess(
                      refSourceTypeInternalName, Type.getObjectType(type).getInternalName()))
              .build());
      super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitInvokeDynamicInsn(
        String name,
        String descriptor,
        Handle bootstrapMethodHandle,
        Object... bootstrapMethodArguments) {
      // This part might be unnecessary...
      addReference(
          new Reference.Builder(bootstrapMethodHandle.getOwner())
              .withSource(refSourceClassName, currentLineNumber)
              .withFlag(
                  computeMinimumClassAccess(
                      refSourceTypeInternalName,
                      Type.getObjectType(bootstrapMethodHandle.getOwner()).getInternalName()))
              .build());
      for (Object arg : bootstrapMethodArguments) {
        if (arg instanceof Handle) {
          Handle handle = (Handle) arg;
          addReference(
              new Reference.Builder(handle.getOwner())
                  .withSource(refSourceClassName, currentLineNumber)
                  .withFlag(
                      computeMinimumClassAccess(
                          refSourceTypeInternalName,
                          Type.getObjectType(handle.getOwner()).getInternalName()))
                  .build());
        }
      }
      super.visitInvokeDynamicInsn(
          name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }

    @Override
    public void visitLdcInsn(final Object value) {
      if (value instanceof Type) {
        final Type type = underlyingType((Type) value);
        String typeInternalName = type.getInternalName();
        if (type.getSort() == Type.OBJECT) {
          addReference(
              new Reference.Builder(typeInternalName)
                  .withSource(refSourceClassName, currentLineNumber)
                  .withFlag(computeMinimumClassAccess(refSourceTypeInternalName, typeInternalName))
                  .build());
        }
      }
      super.visitLdcInsn(value);
    }
  }
}

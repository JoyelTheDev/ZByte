package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.List;

@TransformerConfig.ConfigOptions(configClass = InvokeDynamicInliningTransformer.Config.class)
public class InvokeDynamicInliningTransformer extends Transformer<InvokeDynamicInliningTransformer.Config> {

    @Override
    public boolean transform() throws Throwable {
        IndyContext context = new IndyContext(classes, classpath, getConfig().getMaxSteps());
        List<InvokeDynamicResolver> resolvers = InvokeDynamicResolverManager.createResolvers();
        for (InvokeDynamicResolver resolver : resolvers) {
            resolver.setup(context);
        }
        boolean changed = false;
        for (ClassNode classNode : new ArrayList<>(classNodes())) {
            for (MethodNode method : new ArrayList<>(classNode.methods)) {
                if (method.instructions == null || method.instructions.size() == 0 || !containsIndy(method)) {
                    continue;
                }
                Frame<ReValue>[] frames;
                try {
                    frames = context.analysis().analyze(classNode, method);
                } catch (Throwable ex) {
                    logger.debug("Failed to analyze {}.{}{} for invokedynamic inlining", classNode.name, method.name, method.desc);
                    continue;
                }
                for (int index = method.instructions.size() - 1; index >= 0; index--) {
                    AbstractInsnNode insn = method.instructions.get(index);
                    if (!(insn instanceof InvokeDynamicInsnNode)) {
                        continue;
                    }
                    InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                    Frame<ReValue> frame = index < frames.length ? frames[index] : null;
                    for (InvokeDynamicResolver resolver : resolvers) {
                        try {
                            InvokeDynamicResolver.ResolvedInvokeDynamic resolution = frame != null
                                    ? resolver.resolve(context, classNode, method, indy, frame)
                                    : resolver.resolveWithoutFrame(context, classNode, method, indy);
                            if (resolution == null) {
                                continue;
                            }
                            if (rewrite(method, indy, resolution)) {
                                changed = true;
                                break;
                            }
                        } catch (Exception ex) {
                            logger.debug("Resolver {} failed at {}.{}{}", resolver.getClass().getName(), classNode.name, method.name, method.desc);
                        }
                    }
                }
            }
        }
        return changed;
    }

    private static boolean containsIndy(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof InvokeDynamicInsnNode) {
                return true;
            }
        }
        return false;
    }

    private static VarInsnNode varLoad(int index, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return new VarInsnNode(Opcodes.ILOAD, index);
            case Type.FLOAT:
                return new VarInsnNode(Opcodes.FLOAD, index);
            case Type.DOUBLE:
                return new VarInsnNode(Opcodes.DLOAD, index);
            case Type.LONG:
                return new VarInsnNode(Opcodes.LLOAD, index);
            case Type.ARRAY:
            case Type.OBJECT:
                return new VarInsnNode(Opcodes.ALOAD, index);
            default:
                throw new IllegalArgumentException("Unsupported variable type: " + type);
        }
    }

    private static VarInsnNode varStore(int index, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return new VarInsnNode(Opcodes.ISTORE, index);
            case Type.FLOAT:
                return new VarInsnNode(Opcodes.FSTORE, index);
            case Type.DOUBLE:
                return new VarInsnNode(Opcodes.DSTORE, index);
            case Type.LONG:
                return new VarInsnNode(Opcodes.LSTORE, index);
            case Type.ARRAY:
            case Type.OBJECT:
                return new VarInsnNode(Opcodes.ASTORE, index);
            default:
                throw new IllegalArgumentException("Unsupported variable type: " + type);
        }
    }

	
	private static boolean rewrite(MethodNode method, InvokeDynamicInsnNode instruction,
	                               InvokeDynamicResolver.ResolvedInvokeDynamic resolution) {
		try {
			Handle target = resolution.target();
			Type[] callSiteArguments = Type.getArgumentTypes(instruction.desc);
			Type callSiteReturn = Type.getReturnType(instruction.desc);
			int suffixCount = resolution.ignoredTrailingArgumentCount();
			if (suffixCount < 0 || suffixCount > callSiteArguments.length)
				return false;

			int representedArgumentCount = callSiteArguments.length - suffixCount;
			int targetArgumentCount = targetArgumentCount(target);
			if (representedArgumentCount != targetArgumentCount || !isSupportedTarget(target))
				return false;

			int nextLocal = Math.max(0, method.maxLocals);
			int[] argumentLocals = new int[callSiteArguments.length];
			for (int i = 0; i < callSiteArguments.length; i++) {
				Type argument = callSiteArguments[i];
				if (argument.getSort() == Type.VOID || argument.getSort() == Type.METHOD)
					return false;
				argumentLocals[i] = nextLocal;
				nextLocal += argument.getSize();
			}

			InsnList replacement = new InsnList();
			for (int i = callSiteArguments.length - 1; i >= 0; i--) {
				Type argType = callSiteArguments[i];
				int argIndex = argumentLocals[i];
				replacement.add(varStore(argIndex, argType));
			}
			Type[] targetArguments = targetArguments(target);
			for (int i = 0; i < representedArgumentCount; i++) {
				Type source = callSiteArguments[i];
				Type destination = targetArguments[i];
				int ardIndex = argumentLocals[i];
				replacement.add(varLoad(ardIndex, source));
				if (!appendConversion(source, destination, replacement))
					return false;
			}
			replacement.add(targetInstruction(target));
			if (!appendReturnConversion(targetReturnType(target), callSiteReturn, replacement))
				return false;

			method.instructions.insertBefore(instruction, replacement);
			method.instructions.remove(instruction);
			method.maxLocals = Math.max(method.maxLocals, nextLocal);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	
	private static boolean isSupportedTarget(Handle target) {
		int tag = target.getTag();
		return switch (tag) {
			case Opcodes.H_GETFIELD, Opcodes.H_PUTFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTSTATIC,
			     Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESTATIC,
			     Opcodes.H_INVOKESPECIAL -> !((tag == Opcodes.H_INVOKESPECIAL) && target.getName().equals("<init>"));
			default -> false;
		};
	}

	
	private static int targetArgumentCount(Handle target) {
		return switch (target.getTag()) {
			case Opcodes.H_GETFIELD -> 1;
			case Opcodes.H_GETSTATIC -> 0;
			case Opcodes.H_PUTFIELD -> 2;
			case Opcodes.H_PUTSTATIC -> 1;
			case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESPECIAL ->
					Type.getArgumentCount(target.getDesc()) + 1;
			case Opcodes.H_INVOKESTATIC -> Type.getArgumentCount(target.getDesc());
			default -> -1;
		};
	}

	
	private static Type[] targetArguments(Handle target) {
		return switch (target.getTag()) {
			case Opcodes.H_GETFIELD -> new Type[]{Type.getObjectType(target.getOwner())};
			case Opcodes.H_PUTFIELD ->
					new Type[]{Type.getObjectType(target.getOwner()), Type.getType(target.getDesc())};
			case Opcodes.H_GETSTATIC -> new Type[0];
			case Opcodes.H_PUTSTATIC -> new Type[]{Type.getType(target.getDesc())};
			case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESPECIAL -> {
				Type[] arguments = Type.getArgumentTypes(target.getDesc());
				Type[] result = new Type[arguments.length + 1];
				result[0] = Type.getObjectType(target.getOwner());
				System.arraycopy(arguments, 0, result, 1, arguments.length);
				yield result;
			}
			case Opcodes.H_INVOKESTATIC -> Type.getArgumentTypes(target.getDesc());
			default -> throw new IllegalArgumentException("Unsupported handle tag: " + target.getTag());
		};
	}

	
	private static Type targetReturnType(Handle target) {
		return switch (target.getTag()) {
			case Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC -> Type.getType(target.getDesc());
			case Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC -> Type.VOID_TYPE;
			case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKESPECIAL ->
					Type.getReturnType(target.getDesc());
			default -> throw new IllegalArgumentException("Unsupported handle tag: " + target.getTag());
		};
	}

	
	private static AbstractInsnNode targetInstruction(Handle target) {
		String owner = target.getOwner();
		String name = target.getName();
		String desc = target.getDesc();
		boolean itf = target.isInterface();
		return switch (target.getTag()) {
			case Opcodes.H_GETFIELD -> new FieldInsnNode(Opcodes.GETFIELD, owner, name, desc);
			case Opcodes.H_PUTFIELD -> new FieldInsnNode(Opcodes.PUTFIELD, owner, name, desc);
			case Opcodes.H_GETSTATIC -> new FieldInsnNode(Opcodes.GETSTATIC, owner, name, desc);
			case Opcodes.H_PUTSTATIC -> new FieldInsnNode(Opcodes.PUTSTATIC, owner, name, desc);
			case Opcodes.H_INVOKEVIRTUAL -> new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, name, desc, itf);
			case Opcodes.H_INVOKEINTERFACE -> new MethodInsnNode(Opcodes.INVOKEINTERFACE, owner, name, desc, true);
			case Opcodes.H_INVOKESTATIC -> new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, itf);
			case Opcodes.H_INVOKESPECIAL -> new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, name, desc, itf);
			default -> throw new IllegalArgumentException("Unsupported handle tag: " + target.getTag());
		};
	}

	
	private static boolean appendConversion(Type source, Type destination, InsnList output) {
		if (source.equals(destination))
			return true;

		if (isPrimitive(source) && isPrimitive(destination))
			return appendPrimitiveConversion(source, destination, output);

		if (isReference(source) && isReference(destination)) {
			appendCheckCast(destination, output);
			return true;
		}

		if (isPrimitive(source) && isReference(destination)) {
			Type wrapper = primitiveWrapper(source);
			output.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper.getInternalName(), "valueOf", "(" + source.getDescriptor() + ")" + wrapper.getDescriptor(), false));
			if (!wrapper.equals(destination))
				appendCheckCast(destination, output);
			return true;
		}
		if (isReference(source) && isPrimitive(destination)) {
			Type wrapper = primitiveWrapper(destination);
			if (!source.equals(wrapper))
				appendCheckCast(wrapper, output);
			output.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, wrapper.getInternalName(), primitiveValueMethod(destination), "()" + destination.getDescriptor(), false));
			return true;
		}
		return false;
	}

	
	private static boolean appendReturnConversion(Type source, Type destination, InsnList output) {
		if (Type.VOID_TYPE.equals(destination)) {
			if (!Type.VOID_TYPE.equals(source))
				output.add(new InsnNode(source.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
			return true;
		}

		if (Type.VOID_TYPE.equals(source))
			return appendDefaultValue(destination, output);

		return appendConversion(source, destination, output);
	}

	
	private static boolean appendDefaultValue(Type type, InsnList output) {
		switch (type.getSort()) {
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> output.add(new InsnNode(Opcodes.ICONST_0));
			case Type.FLOAT -> output.add(new InsnNode(Opcodes.FCONST_0));
			case Type.LONG -> output.add(new InsnNode(Opcodes.LCONST_0));
			case Type.DOUBLE -> output.add(new InsnNode(Opcodes.DCONST_0));
			case Type.ARRAY, Type.OBJECT -> output.add(new InsnNode(Opcodes.ACONST_NULL));
			default -> {
				return false;
			}
		}
		return true;
	}

	
	private static boolean appendPrimitiveConversion(Type source, Type destination, InsnList output) {
		int sourceSort = primitiveCategory(source);
		int destinationSort = primitiveCategory(destination);
		if (sourceSort == destinationSort) {
			appendIntegerNarrowing(destination, output);
			return true;
		}

		if (destinationSort == Type.INT) {
			switch (sourceSort) {
				case Type.LONG -> output.add(new InsnNode(Opcodes.L2I));
				case Type.FLOAT -> output.add(new InsnNode(Opcodes.F2I));
				case Type.DOUBLE -> output.add(new InsnNode(Opcodes.D2I));
				case Type.INT -> {
				}
				default -> {
					return false;
				}
			}
			appendIntegerNarrowing(destination, output);
			return true;
		}
		if (sourceSort == Type.INT) {
			switch (destinationSort) {
				case Type.LONG -> output.add(new InsnNode(Opcodes.I2L));
				case Type.FLOAT -> output.add(new InsnNode(Opcodes.I2F));
				case Type.DOUBLE -> output.add(new InsnNode(Opcodes.I2D));
				default -> {
					return false;
				}
			}
			return true;
		}
		if (sourceSort == Type.LONG) {
			switch (destinationSort) {
				case Type.FLOAT -> output.add(new InsnNode(Opcodes.L2F));
				case Type.DOUBLE -> output.add(new InsnNode(Opcodes.L2D));
				default -> {
					return false;
				}
			}
			return true;
		}
		if (sourceSort == Type.FLOAT) {
			switch (destinationSort) {
				case Type.LONG -> output.add(new InsnNode(Opcodes.F2L));
				case Type.DOUBLE -> output.add(new InsnNode(Opcodes.F2D));
				default -> {
					return false;
				}
			}
			return true;
		}
		if (sourceSort == Type.DOUBLE) {
			switch (destinationSort) {
				case Type.LONG -> output.add(new InsnNode(Opcodes.D2L));
				case Type.FLOAT -> output.add(new InsnNode(Opcodes.D2F));
				default -> {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	
	private static void appendIntegerNarrowing(Type destination, InsnList output) {
		switch (destination.getSort()) {
			case Type.BOOLEAN -> {
				output.add(new InsnNode(Opcodes.ICONST_1));
				output.add(new InsnNode(Opcodes.IAND));
			}
			case Type.BYTE -> output.add(new InsnNode(Opcodes.I2B));
			case Type.CHAR -> output.add(new InsnNode(Opcodes.I2C));
			case Type.SHORT -> output.add(new InsnNode(Opcodes.I2S));
			default -> {}
		}
	}

	
	private static void appendCheckCast(Type destination, InsnList output) {
		String castType = destination.getSort() == Type.ARRAY ? destination.getDescriptor() : destination.getInternalName();
		output.add(new TypeInsnNode(Opcodes.CHECKCAST, castType));
	}

	
	private static int primitiveCategory(Type type) {
		return switch (type.getSort()) {
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Type.INT;
			case Type.FLOAT -> Type.FLOAT;
			case Type.LONG -> Type.LONG;
			case Type.DOUBLE -> Type.DOUBLE;
			default -> -1; // Object, array, void, method, etc.
		};
	}

	
	private static boolean isPrimitive(Type type) {
		return primitiveCategory(type) >= 0;
	}

	
	private static boolean isReference(Type type) {
		return type.getSort() == Type.ARRAY || type.getSort() == Type.OBJECT;
	}

	
	private static Type primitiveWrapper(Type primitive) {
		return switch (primitive.getSort()) {
			case Type.BOOLEAN -> Type.getObjectType("java/lang/Boolean");
			case Type.BYTE -> Type.getObjectType("java/lang/Byte");
			case Type.CHAR -> Type.getObjectType("java/lang/Character");
			case Type.SHORT -> Type.getObjectType("java/lang/Short");
			case Type.INT -> Type.getObjectType("java/lang/Integer");
			case Type.FLOAT -> Type.getObjectType("java/lang/Float");
			case Type.LONG -> Type.getObjectType("java/lang/Long");
			case Type.DOUBLE -> Type.getObjectType("java/lang/Double");
			default -> throw new IllegalArgumentException("Not a primitive type: " + primitive);
		};
	}

	
	private static String primitiveValueMethod(Type primitive) {
		return switch (primitive.getSort()) {
			case Type.BOOLEAN -> "booleanValue";
			case Type.BYTE -> "byteValue";
			case Type.CHAR -> "charValue";
			case Type.SHORT -> "shortValue";
			case Type.INT -> "intValue";
			case Type.FLOAT -> "floatValue";
			case Type.LONG -> "longValue";
			case Type.DOUBLE -> "doubleValue";
			default -> throw new IllegalArgumentException("Not a primitive type: " + primitive);
		};
	}

    public static class Config extends TransformerConfig {

        private int maxSteps = 20000;

        public Config() {
            super(InvokeDynamicInliningTransformer.class);
        }

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }
    }
}

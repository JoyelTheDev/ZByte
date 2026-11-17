package dev.joyel.zbyte.transformers.zkm;

import dev.joyel.zbyte.reanalysis.ClassEvaluator;
import dev.joyel.zbyte.reanalysis.value.ReValue;
import dev.joyel.zbyte.reanalysis.value.LongValue;
import dev.joyel.zbyte.reanalysis.value.IntValue;
import dev.joyel.zbyte.reanalysis.value.StringValue;
import dev.joyel.zbyte.transformers.universal.IndyContext;
import dev.joyel.zbyte.transformers.universal.InvokeDynamicResolver;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import static org.objectweb.asm.Opcodes.*;

public class ZkmInvokeDynamicResolver implements InvokeDynamicResolver {
	
	public static final String BOOTSTRAP_DESCRIPTOR =
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
	private static final int DEFAULT_MAX_STEPS = 40_000;
	private static final String RESOLVER_DESCRIPTOR =
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;"
					+ "Ljava/lang/String;Ljava/lang/invoke/MethodType;JJ)Ljava/lang/invoke/MethodHandle;";
	private static final String RESOLVER_DESCRIPTOR_ALT =
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;"
					+ "Ljava/lang/String;Ljava/lang/invoke/MethodType;J)Ljava/lang/invoke/MethodHandle;";

	private final ConcurrentMap<String, Optional<Metadata>> metadataCache = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, ClassState> stateCache = new ConcurrentHashMap<>();

	private static volatile Snapshot lastSnapshot;

	@Override
	public void setup(IndyContext context) {
		metadataCache.clear();
		stateCache.clear();
		Map<String, CleanupMetadata> collected = new HashMap<>();
		for (ClassNode node : context.classes().values()) {
			CleanupMetadata value = discoverCleanupMetadata(node);
			if (value != null)
				collected.put(node.name, value);
		}
		lastSnapshot = new Snapshot(new java.lang.ref.WeakReference<>(context.classes()), Map.copyOf(collected));
	}

	static Map<String, CleanupMetadata> snapshot(Map<String, ClassNode> classes) {
		Snapshot current = lastSnapshot;
		return current != null && current.owner.get() == classes ? current.metadata : null;
	}

	private record Snapshot(java.lang.ref.WeakReference<Map<String, ClassNode>> owner, Map<String, CleanupMetadata> metadata) {}

	@Override
	public int getPriority() {
		return -100;
	}

	@Override
	public InvokeDynamicResolver.ResolvedInvokeDynamic resolve(IndyContext context,
	                                                           ClassNode classNode,
	                                                           MethodNode method,
	                                                           InvokeDynamicInsnNode instruction,
	                                                           Frame<ReValue> frame) {
		PreparedSite site = prepareSite(context, classNode, instruction);
		if (site == null)
			return null;
		long[] metadataKeys = keysFromFrame(frame, site.metadataKeyCount);
		if (metadataKeys == null)
			return null;
		return resolveWithKeys(context, classNode, instruction, site, metadataKeys);
	}

	@Override
	public InvokeDynamicResolver.ResolvedInvokeDynamic resolveWithoutFrame(IndyContext context,
	                                                                       ClassNode classNode,
	                                                                       MethodNode method,
	                                                                       InvokeDynamicInsnNode instruction) {
		PreparedSite site = prepareSite(context, classNode, instruction);
		if (site == null)
			return null;
		long[] metadataKeys = keysFromInstructions(method, instruction, site.metadataKeyCount);
		if (metadataKeys == null)
			return null;
		return resolveWithKeys(context, classNode, instruction, site, metadataKeys);
	}

	private PreparedSite prepareSite(IndyContext context,
	                                 ClassNode classNode,
	                                 InvokeDynamicInsnNode instruction) {
		if (!isZkmSite(instruction))
			return null;

		String helperOwner = instruction.bsm.getOwner();
		ClassNode helperNode = helperOwner.equals(classNode.name) ? classNode : context.classes().get(helperOwner);
		if (helperNode == null)
			return null;

		Type[] callSiteArguments;
		try {
			callSiteArguments = Type.getArgumentTypes(instruction.desc);
		} catch (RuntimeException ignored) {
			return null;
		}

		Metadata metadata = metadataCache.computeIfAbsent(helperOwner, _ -> Optional.ofNullable(discoverMetadata(helperNode))).orElse(null);
		if (metadata == null)
			return null;

		int metadataKeyCount = metadata.metadataKeyCount;
		if (callSiteArguments.length < metadataKeyCount)
			return null;
		for (int index = callSiteArguments.length - metadataKeyCount; index < callSiteArguments.length; index++)
			if (!Type.LONG_TYPE.equals(callSiteArguments[index]))
				return null;
		return new PreparedSite(helperOwner, helperNode, metadata, metadataKeyCount);
	}

	private static long[] keysFromFrame(Frame<ReValue> frame, int metadataKeyCount) {
		int stackSize = frame.getStackSize();
		if (stackSize < metadataKeyCount)
			return null;
		long[] metadataKeys = new long[metadataKeyCount];
		for (int index = 0; index < metadataKeyCount; index++) {
			ReValue key = frame.getStack(stackSize - metadataKeyCount + index);
			if (!(key instanceof LongValue longKey))
				return null;
			OptionalLong value = longKey.value();
			if (value.isEmpty())
				return null;
			metadataKeys[index] = value.getAsLong();
		}
		return metadataKeys;
	}

	private static long[] keysFromInstructions(MethodNode method,
	                                           InvokeDynamicInsnNode instruction,
	                                           int metadataKeyCount) {
		long[] metadataKeys = new long[metadataKeyCount];
		AbstractInsnNode current = getPreviousInsn(instruction);
		for (int index = metadataKeyCount - 1; index >= 0; index--) {
			Long key = longConstant(current);
			if (key == null && current instanceof VarInsnNode load && load.getOpcode() == LLOAD)
				key = findLongLocalConstant(load.var, load);
			if (key == null)
				return null;
			metadataKeys[index] = key;
			current = getPreviousInsn(current);
		}
		return metadataKeys;
	}

	private static Long findLongLocalConstant(int slot, AbstractInsnNode load) {
		Long candidate = null;
		AbstractInsnNode current = getPreviousInsn(load);
		while (current != null) {
			if (current instanceof VarInsnNode store && store.getOpcode() == LSTORE && store.var == slot) {
				Long literal = longConstant(getPreviousInsn(store));
				if (literal == null || candidate != null)
					return null;
				candidate = literal;
			}
			current = getPreviousInsn(current);
		}
		return candidate;
	}

	private static Long longConstant(AbstractInsnNode instruction) {
		if (instruction == null)
			return null;
		return switch (instruction.getOpcode()) {
			case LCONST_0 -> 0L;
			case LCONST_1 -> 1L;
			case LDC -> instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Long value ? value : null;
			default -> null;
		};
	}

	private InvokeDynamicResolver.ResolvedInvokeDynamic resolveWithKeys(IndyContext context,
	                                                                    ClassNode classNode,
	                                                                    InvokeDynamicInsnNode instruction,
	                                                                    PreparedSite site,
	                                                                    long[] metadataKeys) {
		ClassState state = stateCache.computeIfAbsent(site.helperOwner, ignored -> new ClassState(site.helperNode, site.metadata));
		DecodedMember decoded;
		try {
			decoded = state.decode(context, instruction.name, metadataKeys);
		} catch (Exception ignored) {
			return null;
		}
		if (decoded == null)
			return null;

		return resolveMember(context, classNode.name, decoded, site.metadata.operationCodes, site.metadataKeyCount);
	}

	public static boolean isZkmSite(InvokeDynamicInsnNode instruction) {
		if (instruction.bsm == null || !BOOTSTRAP_DESCRIPTOR.equals(instruction.bsm.getDesc()))
			return false;
		String owner = instruction.bsm.getOwner();
		return owner != null && !owner.isEmpty();
	}

	
	private static Metadata discoverMetadata(ClassNode classNode) {
		for (MethodNode method : classNode.methods) {
			if ((method.access & ACC_STATIC) == 0 || !isIndexHelper(classNode, method))
				continue;
			Type[] helperArguments;
			try {
				helperArguments = Type.getArgumentTypes(method.desc);
			} catch (Throwable ignored) {
				continue;
			}

			Set<FieldKey> objectFields = new LinkedHashSet<>();
			Set<FieldKey> descriptorFields = new LinkedHashSet<>();
			collectCacheFields(classNode, method, objectFields, descriptorFields);
			if (objectFields.size() != 1 || descriptorFields.size() != 1)
				continue;

			FieldKey objectField = objectFields.iterator().next();
			FieldKey descriptorField = descriptorFields.iterator().next();
			MethodNode seed = findSeedMethod(classNode, objectField, descriptorField);
			if (seed == null)
				continue;

			Map<Integer, Type> classTypes = discoverClassTypes(seed);
			OperationCodes operationCodes = discoverOperationCodes(classNode);
			return new Metadata(new MethodKey(method.name, method.desc), helperArguments.length, objectField, descriptorField,
					new MethodKey(seed.name, seed.desc), classTypes, operationCodes);
		}
		return null;
	}

	
	static CleanupMetadata discoverCleanupMetadata(ClassNode classNode) {
		Metadata metadata = discoverMetadata(classNode);
		if (metadata == null)
			return discoverResidualCleanupMetadata(classNode);

		Set<MethodKey> helperMethods = new HashSet<>();
		helperMethods.add(metadata.indexMethod);
		helperMethods.add(metadata.seedMethod);
		for (MethodNode method : classNode.methods) {
			boolean privateStatic = (method.access & (ACC_PRIVATE | ACC_STATIC)) == (ACC_PRIVATE | ACC_STATIC);
			boolean sharedEntry = BOOTSTRAP_DESCRIPTOR.equals(method.desc)
					|| isResolverDescriptor(method.desc)
					|| isSpreadBootstrap(method);
			if (!privateStatic && !sharedEntry)
				continue;
			if (sharedEntry || isStringDecoder(method) || isNumericDecoder(method))
				helperMethods.add(new MethodKey(method.name, method.desc));
		}

		boolean changed;
		do {
			changed = false;
			for (MethodKey helper : List.copyOf(helperMethods)) {
				MethodNode method = findMethod(classNode, helper);
				if (method == null || method.instructions == null)
					continue;
				for (AbstractInsnNode instruction : method.instructions) {
					if (!(instruction instanceof MethodInsnNode call) || !classNode.name.equals(call.owner))
						continue;
					MethodNode target = findMethod(classNode, new MethodKey(call.name, call.desc));
					if (target == null || (target.access & ACC_STATIC) == 0)
						continue;
					changed |= helperMethods.add(new MethodKey(target.name, target.desc));
				}
			}
		} while (changed);

		Set<FieldKey> helperFields = new HashSet<>();
		Set<FieldKey> stateFields = new HashSet<>();
		helperFields.add(metadata.objectField);
		helperFields.add(metadata.descriptorField);
		for (FieldNode field : classNode.fields) {
			FieldKey key = new FieldKey(classNode.name, field.name, field.desc);
			if (isPrivateHelperStorage(field) || isInterfaceHelperStorage(classNode, field))
				helperFields.add(key);
			else if ((field.access & ACC_STATIC) != 0
					&& (field.access & ACC_FINAL) == 0
					&& isPrimitive(field.desc))
				stateFields.add(key);
		}

		for (MethodKey helper : helperMethods) {
			MethodNode method = findMethod(classNode, helper);
			if (method == null || method.instructions == null)
				continue;
			for (AbstractInsnNode instruction : method.instructions) {
				if (!(instruction instanceof FieldInsnNode field) || !classNode.name.equals(field.owner))
					continue;

				FieldKey key = new FieldKey(field.owner, field.name, field.desc);
				FieldNode declared = findField(classNode, field.name, field.desc);
				if (declared == null)
					continue;

				if (isPrimitive(declared.desc))
					stateFields.add(key);
				else if (isPrivateHelperStorage(declared) || isInterfaceHelperStorage(classNode, declared))
					helperFields.add(key);
			}
		}
		return new CleanupMetadata(Set.copyOf(helperMethods), Set.copyOf(helperFields), Set.copyOf(stateFields),
				metadata.objectField, metadata.descriptorField);
	}

	
	private static CleanupMetadata discoverResidualCleanupMetadata(ClassNode classNode) {
		Set<MethodKey> helperMethods = new HashSet<>();
		Set<FieldKey> referencedArrayFields = new HashSet<>();
		for (MethodNode method : classNode.methods) {
			if ((method.access & (ACC_PRIVATE | ACC_STATIC)) != (ACC_PRIVATE | ACC_STATIC))
				continue;

			if (isStringDecoder(method) || isNumericDecoder(method))
				helperMethods.add(new MethodKey(method.name, method.desc));

			if ("()V".equals(method.desc) && !"<clinit>".equals(method.name) && hasArrayStore(method))
				helperMethods.add(new MethodKey(method.name, method.desc));

			if (method.instructions == null)
				continue;
			for (AbstractInsnNode instruction : method.instructions) {
				if (!(instruction instanceof FieldInsnNode field)
						|| field.getOpcode() != GETSTATIC
						|| !classNode.name.equals(field.owner)
						|| !isGeneratedArrayDescriptor(field.desc))
					continue;
				if (isStringDecoder(method) || isNumericDecoder(method))
					referencedArrayFields.add(new FieldKey(field.owner, field.name, field.desc));
			}
		}
		if (helperMethods.isEmpty())
			return null;

		Set<FieldKey> helperFields = new HashSet<>();
		for (FieldNode field : classNode.fields) {
			boolean immutable = (field.access & (ACC_PRIVATE | ACC_STATIC | ACC_FINAL))
					== (ACC_PRIVATE | ACC_STATIC | ACC_FINAL);
			if ((immutable && isGeneratedArrayDescriptor(field.desc)) || isInterfaceHelperStorage(classNode, field))
				helperFields.add(new FieldKey(classNode.name, field.name, field.desc));
		}
		if (helperFields.size() < 2
				|| helperFields.stream().noneMatch(field -> isGeneratedArrayDescriptor(field.descriptor()))
				|| !helperFields.containsAll(referencedArrayFields))
			return null;

		boolean changed;
		do {
			changed = false;
			for (MethodKey helper : List.copyOf(helperMethods)) {
				MethodNode method = findMethod(classNode, helper);
				if (method == null || method.instructions == null)
					continue;
				for (AbstractInsnNode instruction : method.instructions) {
					if (!(instruction instanceof MethodInsnNode call) || !classNode.name.equals(call.owner))
						continue;
					MethodNode target = findMethod(classNode, new MethodKey(call.name, call.desc));
					if (target == null || (target.access & (ACC_PRIVATE | ACC_STATIC)) != (ACC_PRIVATE | ACC_STATIC))
						continue;
					changed |= helperMethods.add(new MethodKey(target.name, target.desc));
				}
			}
		} while (changed);

		Set<FieldKey> stateFields = new HashSet<>();
		for (MethodKey helper : helperMethods) {
			MethodNode method = findMethod(classNode, helper);
			if (method == null || method.instructions == null)
				continue;

			for (AbstractInsnNode instruction : method.instructions) {
				if (!(instruction instanceof FieldInsnNode field) || !classNode.name.equals(field.owner))
					continue;

				FieldNode declared = findField(classNode, field.name, field.desc);
				if (declared == null)
					continue;

				FieldKey key = new FieldKey(field.owner, field.name, field.desc);
				if (isPrimitive(declared.desc))
					stateFields.add(key);
				else if (isPrivateHelperStorage(declared) || isInterfaceHelperStorage(classNode, declared))
					helperFields.add(key);
			}
		}
		return new CleanupMetadata(Set.copyOf(helperMethods), Set.copyOf(helperFields), Set.copyOf(stateFields), null, null);
	}

	
	private static boolean hasArrayStore(MethodNode method) {
		if (method.instructions == null)
			return false;
		for (AbstractInsnNode instruction : method.instructions)
			if (instruction.getOpcode() == AASTORE)
				return true;
		return false;
	}

	
	private static boolean isGeneratedArrayDescriptor(String descriptor) {
		return "[Ljava/lang/Object;".equals(descriptor)
				|| "[Ljava/lang/String;".equals(descriptor)
				|| "[J".equals(descriptor)
				|| "[Ljava/lang/Integer;".equals(descriptor)
				|| "[Ljava/lang/Long;".equals(descriptor);
	}

	
	private static boolean isPrivateHelperStorage(FieldNode field) {
		return (field.access & (ACC_PRIVATE | ACC_STATIC | ACC_FINAL))
				== (ACC_PRIVATE | ACC_STATIC | ACC_FINAL)
				&& (isGeneratedArrayDescriptor(field.desc) || isPrimitive(field.desc));
	}

	
	private static boolean isInterfaceHelperStorage(ClassNode classNode, FieldNode field) {
		return (classNode.access & ACC_INTERFACE) != 0
				&& (field.access & (ACC_STATIC | ACC_FINAL)) == (ACC_STATIC | ACC_FINAL)
				&& (isGeneratedArrayDescriptor(field.desc) || "Ljava/lang/String;".equals(field.desc));
	}

	
	private static boolean isSpreadBootstrap(MethodNode method) {
		return ("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;"
				+ "Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/Object;").equals(method.desc);
	}

	
	private static boolean isResolverDescriptor(String descriptor) {
		return RESOLVER_DESCRIPTOR.equals(descriptor) || RESOLVER_DESCRIPTOR_ALT.equals(descriptor);
	}

	
	private static boolean isStringDecoder(MethodNode method) {
		if (method.instructions == null)
			return false;

		if (!("(III)Ljava/lang/String;".equals(method.desc) || "(II)Ljava/lang/String;".equals(method.desc)))
			return false;

		for (AbstractInsnNode instruction : method.instructions)
			if (instruction instanceof FieldInsnNode field
					&& instruction.getOpcode() == GETSTATIC
					&& "[Ljava/lang/String;".equals(field.desc))
				return true;

		return false;
	}

	
	private static boolean isNumericDecoder(MethodNode method) {
		if (method.instructions == null)
			return false;

		if (!("(IJ)I".equals(method.desc) || "(IJ)J".equals(method.desc)))
			return false;

		for (AbstractInsnNode instruction : method.instructions)
			if (instruction instanceof FieldInsnNode field
					&& instruction.getOpcode() == GETSTATIC
					&& ("[J".equals(field.desc)
					|| "[Ljava/lang/Integer;".equals(field.desc)
					|| "[Ljava/lang/Long;".equals(field.desc)))
				return true;
		return false;
	}

	
	private static boolean isIndexHelper(ClassNode classNode, MethodNode method) {
		InsnList instructions = method.instructions;
		if (instructions == null || (method.access & ACC_STATIC) == 0)
			return false;

		Type[] arguments;
		try {
			arguments = Type.getArgumentTypes(method.desc);
			if (!Type.INT_TYPE.equals(Type.getReturnType(method.desc)) || (arguments.length != 1 && arguments.length != 2))
				return false;
			for (Type argument : arguments)
				if (!Type.LONG_TYPE.equals(argument))
					return false;
		} catch (RuntimeException ignored) {
			return false;
		}

		boolean hasXor = false;
		boolean hasOr = false;
		boolean hasLeftShift = false;
		boolean hasSlotShift = false;
		boolean hasSelectorShift = false;
		boolean hasLongToInt = false;
		boolean hasObjectArray = false;
		boolean hasStringArray = false;
		for (AbstractInsnNode instruction : instructions) {
			int opcode = instruction.getOpcode();
			hasXor |= opcode == LXOR;
			hasOr |= opcode == LOR;
			hasLeftShift |= opcode == LSHL && hasNearbyConstant(instructions, instruction, 48);
			hasSlotShift |= opcode == LUSHR && hasNearbyConstant(instructions, instruction, 46);
			hasSelectorShift |= opcode == LUSHR && hasNearbyConstant(instructions, instruction, 42);
			hasLongToInt |= opcode == L2I;
			if (instruction instanceof FieldInsnNode field
					&& opcode == GETSTATIC
					&& classNode.name.equals(field.owner)) {
				hasObjectArray |= "[Ljava/lang/Object;".equals(field.desc);
				hasStringArray |= "[Ljava/lang/String;".equals(field.desc);
			}
		}

		if (arguments.length == 2)
			return hasXor && hasOr && hasLeftShift && hasSlotShift && hasLongToInt && hasObjectArray && hasStringArray;

		return hasSlotShift && hasSelectorShift && hasLongToInt && hasObjectArray && hasStringArray;
	}

	
	private static boolean hasNearbyConstant(InsnList instructions,
	                                         AbstractInsnNode target,
	                                         int expected) {
		AbstractInsnNode current = target.getPrevious();
		while (current != null && current.getOpcode() < 0)
			current = current.getPrevious();
		return current instanceof IntInsnNode intInstruction && intInstruction.operand == expected
				|| current instanceof InsnNode && integerConstant(current) == expected
				|| current instanceof LdcInsnNode && integerConstant(current) == expected;
	}

	
	private static void collectCacheFields(ClassNode classNode,
	                                       MethodNode helperMethod,
	                                       Set<FieldKey> objectFields,
	                                       Set<FieldKey> descriptorFields) {
		if (helperMethod.instructions == null)
			return;
		for (AbstractInsnNode instruction : helperMethod.instructions) {
			if (!(instruction instanceof FieldInsnNode field)
					|| field.getOpcode() != GETSTATIC
					|| !classNode.name.equals(field.owner))
				continue;
			FieldKey key = new FieldKey(field.owner, field.name, field.desc);
			if ("[Ljava/lang/Object;".equals(field.desc))
				objectFields.add(key);
			else if ("[Ljava/lang/String;".equals(field.desc))
				descriptorFields.add(key);
		}
	}

	
	private static MethodNode findSeedMethod(ClassNode classNode,
	                                         FieldKey objectField,
	                                         FieldKey descriptorField) {
		for (MethodNode method : classNode.methods) {
			if ("<clinit>".equals(method.name)
					|| (method.access & ACC_STATIC) == 0
					|| !"()V".equals(method.desc)
					|| method.instructions == null)
				continue;
			boolean objectRead = false;
			boolean descriptorRead = false;
			boolean arrayStore = false;
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC) {
					FieldKey key = new FieldKey(field.owner, field.name, field.desc);
					objectRead |= objectField.equals(key);
					descriptorRead |= descriptorField.equals(key);
				}
				arrayStore |= instruction.getOpcode() == AASTORE;
			}
			if (objectRead && descriptorRead && arrayStore)
				return method;
		}
		return null;
	}

	
	private static Map<Integer, Type> discoverClassTypes(MethodNode seed) {
		if (seed.instructions == null)
			return Map.of();
		AbstractInsnNode[] instructions = seed.instructions.toArray();
		Map<Integer, Type> result = new HashMap<>();
		for (int index = 0; index < instructions.length; index++) {
			AbstractInsnNode instruction = instructions[index];
			Type classType = null;
			if (instruction instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC)
				classType = primitiveType(field);
			else if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Type type)
				classType = type;
			if (classType == null)
				continue;
			Integer slot = findStoredIndex(instructions, index);
			if (slot != null)
				result.putIfAbsent(slot, classType);
		}
		return Map.copyOf(result);
	}

	private static Type primitiveType(FieldInsnNode field) {
		if (!"java/lang/Boolean".equals(field.owner) && !"java/lang/Byte".equals(field.owner)
				&& !"java/lang/Character".equals(field.owner) && !"java/lang/Short".equals(field.owner)
				&& !"java/lang/Integer".equals(field.owner) && !"java/lang/Long".equals(field.owner)
				&& !"java/lang/Float".equals(field.owner) && !"java/lang/Double".equals(field.owner)
				&& !"java/lang/Void".equals(field.owner))
			return null;
		if (!"TYPE".equals(field.name) || !"Ljava/lang/Class;".equals(field.desc))
			return null;
		return switch (field.owner) {
			case "java/lang/Boolean" -> Type.BOOLEAN_TYPE;
			case "java/lang/Byte" -> Type.BYTE_TYPE;
			case "java/lang/Character" -> Type.CHAR_TYPE;
			case "java/lang/Short" -> Type.SHORT_TYPE;
			case "java/lang/Integer" -> Type.INT_TYPE;
			case "java/lang/Long" -> Type.LONG_TYPE;
			case "java/lang/Float" -> Type.FLOAT_TYPE;
			case "java/lang/Double" -> Type.DOUBLE_TYPE;
			case "java/lang/Void" -> Type.VOID_TYPE;
			default -> null;
		};
	}

	private static Integer findStoredIndex(AbstractInsnNode[] instructions, int valueIndex) {
		boolean hasStore = false;
		for (int index = valueIndex + 1; index < instructions.length && index <= valueIndex + 8; index++) {
			int opcode = instructions[index].getOpcode();
			if (opcode == AASTORE) {
				hasStore = true;
				break;
			}
			if (opcode == GETSTATIC)
				break;
		}
		if (!hasStore)
			return null;
		for (int index = valueIndex - 1; index >= 0 && index >= valueIndex - 10; index--) {
			Integer constant = integerConstant(instructions[index]);
			if (constant != null)
				return constant;
			if (instructions[index].getOpcode() == AASTORE)
				break;
		}
		return null;
	}

	private static Integer integerConstant(AbstractInsnNode instruction) {
		return switch (instruction.getOpcode()) {
			case Opcodes.ICONST_M1 -> -1;
			case Opcodes.ICONST_0 -> 0;
			case Opcodes.ICONST_1 -> 1;
			case Opcodes.ICONST_2 -> 2;
			case Opcodes.ICONST_3 -> 3;
			case Opcodes.ICONST_4 -> 4;
			case Opcodes.ICONST_5 -> 5;
			case Opcodes.BIPUSH, Opcodes.SIPUSH -> ((IntInsnNode) instruction).operand;
			case Opcodes.LDC -> {
				Object constant = ((LdcInsnNode) instruction).cst;
				yield constant instanceof Integer value ? value : null;
			}
			default -> null;
		};
	}

	
	private static OperationCodes discoverOperationCodes(ClassNode classNode) {
		for (MethodNode method : classNode.methods) {
			if (!isResolverDescriptor(method.desc))
				continue;
			int getField = findOperationCode(method, "findGetter");
			int putField = findOperationCode(method, "findSetter");
			int getStatic = findOperationCode(method, "findStaticGetter");
			int invokeVirtual = findOperationCode(method, "findVirtual");
			int invokeStatic = findOperationCode(method, "findStatic");

			Set<Integer> comparisonCodes = comparisonCodes(method);
			Set<Integer> fieldCodes = new HashSet<>(comparisonCodes);
			fieldCodes.remove(getField);
			fieldCodes.remove(putField);
			fieldCodes.remove(getStatic);
			fieldCodes.remove(invokeVirtual);
			fieldCodes.remove(invokeStatic);
			int putStatic = fieldCodes.size() == 1 ? fieldCodes.iterator().next() : -1;
			return new OperationCodes(getField, putField, getStatic, putStatic, invokeVirtual, invokeStatic);
		}
		return OperationCodes.EMPTY;
	}

	
	private static int findOperationCode(MethodNode resolver, String lookupName) {
		if (resolver.instructions == null)
			return -1;
		AbstractInsnNode[] instructions = resolver.instructions.toArray();
		for (int index = 0; index < instructions.length; index++) {
			AbstractInsnNode instruction = instructions[index];
			if (!(instruction instanceof MethodInsnNode method)
					|| !"java/lang/invoke/MethodHandles$Lookup".equals(method.owner)
					|| !lookupName.equals(method.name))
				continue;
			for (int comparison = index - 1; comparison >= 0 && index - comparison <= 40; comparison--) {
				if (!isIntegerComparison(instructions[comparison].getOpcode()))
					continue;
				for (int constant = comparison - 1; constant >= 0 && comparison - constant <= 12; constant--) {
					Integer value = integerConstant(instructions[constant]);
					if (value != null)
						return value;
				}
			}
		}
		return -1;
	}

	
	private static Set<Integer> comparisonCodes(MethodNode resolver) {
		if (resolver.instructions == null)
			return Set.of();
		AbstractInsnNode[] instructions = resolver.instructions.toArray();
		Set<Integer> result = new HashSet<>();
		for (int index = 0; index < instructions.length; index++) {
			if (!isIntegerComparison(instructions[index].getOpcode()))
				continue;
			for (int constant = index - 1; constant >= 0 && index - constant <= 12; constant--) {
				Integer value = integerConstant(instructions[constant]);
				if (value != null) {
					result.add(value);
					break;
				}
			}
		}
		return result;
	}

	private static boolean isIntegerComparison(int opcode) {
		return switch (opcode) {
			case Opcodes.IF_ICMPEQ,
			     Opcodes.IF_ICMPNE,
			     Opcodes.IF_ICMPLT,
			     Opcodes.IF_ICMPGE,
			     Opcodes.IF_ICMPGT,
			     Opcodes.IF_ICMPLE -> true;
			default -> false;
		};
	}

	
	private static InvokeDynamicResolver.ResolvedInvokeDynamic resolveMember(IndyContext context,
	                                                                         String caller,
	                                                                         DecodedMember member,
	                                                                         OperationCodes operationCodes,
	                                                                         int metadataKeyCount) {
		int operation = operation(member.operationName);
		if (operation < 0 || !operationCodes.hasKnownOperation())
			return null;

		if (matches(operation, operationCodes.getField)
				|| matches(operation, operationCodes.putField)
				|| matches(operation, operationCodes.getStatic)
				|| matches(operation, operationCodes.putStatic)) {
			if (!member.parameterTypes.isEmpty())
				return null;

			FieldMatch match = findField(context, member.owner, member.memberName, member.returnType, new HashSet<>());
			if (match == null || !isAccessible(context, caller, match.ownerInfo, match.member.access))
				return null;

			boolean isStatic = (match.member.access & ACC_STATIC) != 0;
			if (matches(operation, operationCodes.getStatic) || matches(operation, operationCodes.putStatic)) {
				if (!isStatic)
					return null;
			} else if (isStatic) {
				return null;
			}

			int tag = matches(operation, operationCodes.getField) ? H_GETFIELD
					: matches(operation, operationCodes.putField) ? H_PUTFIELD
					: matches(operation, operationCodes.getStatic) ? H_GETSTATIC : H_PUTSTATIC;
			return new InvokeDynamicResolver.ResolvedInvokeDynamic(
					new Handle(tag, match.ownerName, match.member.name, match.member.desc,
							(match.ownerInfo.access & ACC_INTERFACE) != 0), metadataKeyCount);
		}

		if (matches(operation, operationCodes.invokeStatic))
			return resolveMethod(context, caller, member, H_INVOKESTATIC, metadataKeyCount);
		if (matches(operation, operationCodes.invokeVirtual))
			return resolveMethod(context, caller, member, -1, metadataKeyCount);

		return resolveMethod(context, caller, member, H_INVOKESPECIAL, metadataKeyCount);
	}

	private static int operation(String name) {
		return name.isEmpty() ? -1 : name.charAt(0);
	}

	private static boolean matches(int operation, int code) {
		return code >= 0 && operation == code;
	}

	private static InvokeDynamicResolver.ResolvedInvokeDynamic resolveMethod(IndyContext context,
	                                                                         String caller,
	                                                                         DecodedMember member,
	                                                                         int requestedTag,
	                                                                         int metadataKeyCount) {
		String descriptor;
		try {
			descriptor = Type.getMethodDescriptor(member.returnType, member.parameterTypes.toArray(Type[]::new));
		} catch (RuntimeException ignored) {
			return null;
		}

		MethodMatch match = findMethod(context, member.owner, member.memberName, descriptor, new HashSet<>());
		if (match == null
				|| "<init>".equals(match.member.name)
				|| !isAccessible(context, caller, match.ownerInfo, match.member.access))
			return null;

		boolean isStatic = (match.member.access & ACC_STATIC) != 0;
		if (requestedTag == H_INVOKESTATIC && !isStatic)
			return null;
		if (requestedTag != H_INVOKESTATIC && isStatic)
			return null;
		if (requestedTag == H_INVOKESPECIAL && !isSpecialLookupAllowed(context, caller, match.ownerName, match.ownerInfo))
			return null;

		int tag = requestedTag;
		boolean ownerIsInterface = (match.ownerInfo.access & ACC_INTERFACE) != 0;
		if (tag < 0)
			tag = ownerIsInterface ? H_INVOKEINTERFACE : H_INVOKEVIRTUAL;
		return new InvokeDynamicResolver.ResolvedInvokeDynamic(
				new Handle(tag, match.ownerName, match.member.name, match.member.desc, ownerIsInterface), metadataKeyCount);
	}

	private static boolean isSpecialLookupAllowed(IndyContext context, String caller, String owner, ClassNode ownerInfo) {
		if (caller.equals(owner))
			return true;
		return context.hierarchy().isAssignableFrom(owner, caller) && (ownerInfo.access & ACC_ANNOTATION) == 0;
	}

	private static boolean isAccessible(IndyContext context, String caller, ClassNode owner, int memberAccess) {
		if (!isClassAccessible(caller, owner))
			return false;
		if ((memberAccess & ACC_PUBLIC) != 0)
			return true;
		if ((memberAccess & ACC_PRIVATE) != 0)
			return caller.equals(owner.name);
		if ((memberAccess & ACC_PROTECTED) != 0)
			return samePackage(caller, owner.name) || context.hierarchy().isAssignableFrom(owner.name, caller);
		return samePackage(caller, owner.name);
	}

	private static boolean isClassAccessible(String caller, ClassNode owner) {
		return (owner.access & ACC_PUBLIC) != 0 || samePackage(caller, owner.name);
	}

	private static boolean samePackage(String first, String second) {
		int firstSlash = first.lastIndexOf('/');
		int secondSlash = second.lastIndexOf('/');
		if (firstSlash < 0 || secondSlash < 0)
			return firstSlash < 0 && secondSlash < 0;
		return first.substring(0, firstSlash).equals(second.substring(0, secondSlash));
	}

	private static FieldMatch findField(IndyContext context,
	                                    String owner,
	                                    String name,
	                                    Type type,
	                                    Set<String> visited) {
		if (!visited.add(owner))
			return null;

		if (type.getSort() == Type.METHOD)
			return null;

		ClassNode info = context.findClass(owner);
		if (info == null)
			return null;

		FieldNode field = findField(info, name, type.getDescriptor());
		if (field != null)
			return new FieldMatch(owner, info, field);

		for (String interfaceName : info.interfaces) {
			FieldMatch match = findField(context, interfaceName, name, type, visited);
			if (match != null)
				return match;
		}
		String superName = info.superName;
		return superName == null ? null : findField(context, superName, name, type, visited);
	}

	private static MethodMatch findMethod(IndyContext context,
	                                      String owner,
	                                      String name,
	                                      String descriptor,
	                                      Set<String> visited) {
		if (!visited.add(owner))
			return null;

		ClassNode info = context.findClass(owner);
		if (info == null)
			return null;

		MethodNode method = null;
		for (MethodNode candidate : info.methods)
			if (candidate.name.equals(name) && candidate.desc.equals(descriptor))
				method = candidate;
		if (method != null)
			return new MethodMatch(owner, info, method);

		for (String interfaceName : info.interfaces) {
			MethodMatch match = findMethod(context, interfaceName, name, descriptor, visited);
			if (match != null)
				return match;
		}
		String superName = info.superName;
		return superName == null ? null : findMethod(context, superName, name, descriptor, visited);
	}

	private static AbstractInsnNode getPreviousInsn(AbstractInsnNode instruction) {
		AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
		while (current != null && current.getOpcode() < 0)
			current = current.getPrevious();
		return current;
	}

	private static boolean isPrimitive(String descriptor) {
		return descriptor.length() == 1 && "ZBCSIJFD".indexOf(descriptor.charAt(0)) >= 0;
	}

	private static Type parseClassName(String value) {
		if (value == null || value.isEmpty())
			return null;
		return switch (value) {
			case "boolean" -> Type.BOOLEAN_TYPE;
			case "byte" -> Type.BYTE_TYPE;
			case "char" -> Type.CHAR_TYPE;
			case "short" -> Type.SHORT_TYPE;
			case "int" -> Type.INT_TYPE;
			case "long" -> Type.LONG_TYPE;
			case "float" -> Type.FLOAT_TYPE;
			case "double" -> Type.DOUBLE_TYPE;
			case "void" -> Type.VOID_TYPE;
			default -> parseNonPrimitiveClassName(value);
		};
	}

	private static Type parseNonPrimitiveClassName(String value) {
		String normalized = value.replace('.', '/');
		try {
			if (normalized.charAt(0) == '[')
				return Type.getType(normalized);
			if (normalized.startsWith("L") && normalized.endsWith(";"))
				return Type.getType(normalized);
			if (normalized.endsWith("[]")) {
				String base = normalized;
				int dimensions = 0;
				while (base.endsWith("[]")) {
					base = base.substring(0, base.length() - 2);
					dimensions++;
				}
				Type baseType = parseClassName(base);
				if (baseType == null || baseType == Type.VOID_TYPE)
					return null;
				return Type.getType("[".repeat(dimensions) + baseType.getDescriptor());
			}
			return Type.getObjectType(normalized);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static MethodNode findMethod(ClassNode classNode, MethodKey key) {
		for (MethodNode method : classNode.methods)
			if (key.name.equals(method.name) && key.descriptor.equals(method.desc))
				return method;
		return null;
	}

	private static FieldNode findField(ClassNode classNode, String name, String descriptor) {
		for (FieldNode field : classNode.fields)
			if (name.equals(field.name) && descriptor.equals(field.desc))
				return field;
		return null;
	}

	private static Map<LabelNode, LabelNode> cloneLabels(MethodNode source) {
		Map<LabelNode, LabelNode> labels = new HashMap<>();
		if (source.instructions != null)
			for (AbstractInsnNode instruction : source.instructions)
				if (instruction instanceof LabelNode label)
					labels.put(label, new LabelNode());
		return labels;
	}

	
	private static MethodNode createCachePrefix(MethodNode source, Set<FieldKey> requiredFields) {
		if (source.instructions == null)
			return null;

		Set<FieldKey> remaining = new HashSet<>(requiredFields);

		MethodNode prefix = new MethodNode(ACC_STATIC, "<clinit>", "()I", null, null);
		Map<LabelNode, LabelNode> labels = cloneLabels(source);
		for (AbstractInsnNode instruction : source.instructions) {
			prefix.instructions.add(instruction.clone(labels));
			if (instruction instanceof FieldInsnNode field && instruction.getOpcode() == Opcodes.PUTSTATIC)
				remaining.remove(new FieldKey(field.owner, field.name, field.desc));
			if (remaining.isEmpty())
				break;
		}
		if (!remaining.isEmpty())
			return null;

		prefix.instructions.add(new InsnNode(Opcodes.ICONST_0));
		prefix.instructions.add(new InsnNode(Opcodes.IRETURN));
		prefix.maxStack = Math.max(source.maxStack, 4);
		prefix.maxLocals = source.maxLocals;
		return prefix;
	}

	
	private static MethodNode createValueReturningMethod(MethodNode source) {
		if (source.instructions == null)
			return null;

		MethodNode entry = new MethodNode(ACC_STATIC, "<clinit>", "()I", null, null);
		Map<LabelNode, LabelNode> labels = cloneLabels(source);
		for (AbstractInsnNode instruction : source.instructions) {
			if (instruction.getOpcode() == Opcodes.RETURN) {
				entry.instructions.add(new InsnNode(Opcodes.ICONST_0));
				entry.instructions.add(new InsnNode(Opcodes.IRETURN));
				break;
			}
			entry.instructions.add(instruction.clone(labels));
		}
		if (entry.instructions.size() == 0 || entry.instructions.getLast().getOpcode() != Opcodes.IRETURN)
			return null;
		entry.maxStack = Math.max(source.maxStack, 4);
		entry.maxLocals = source.maxLocals;
		return entry;
	}

	private static final class ClassState {
		private final ClassNode classNode;
		private final Metadata metadata;
		private final MethodNode indexMethod;
		private boolean initialized;
		private boolean usable;
		private ClassEvaluator evaluator;

		private ClassState(ClassNode classNode, Metadata metadata) {
			this.classNode = classNode;
			this.metadata = metadata;
			indexMethod = findMethod(classNode, metadata.indexMethod);
		}

		private synchronized DecodedMember decode(IndyContext context, String operationName, long[] metadataKeys) {
			if (!initialize(context) || indexMethod == null || metadataKeys.length != metadata.metadataKeyCount)
				return null;
			Integer slot = evaluateIndex(indexMethod, metadataKeys);
			if (slot == null)
				return null;
			String descriptor = readString(metadata.descriptorField, slot);
			if (descriptor == null)
				return null;
			String[] parts = descriptor.split("\u0008", -1);
			if (parts.length < 3 || parts[0].isEmpty() || parts[1].isEmpty())
				return null;
			Type ownerType = resolveClassToken(parts[0]);
			if (ownerType == null || ownerType.getSort() != Type.OBJECT)
				return null;
			boolean methodDescriptor = parts.length > 3 && parts[parts.length - 1].isEmpty();
			int returnIndex = methodDescriptor ? parts.length - 2 : parts.length - 1;
			if (returnIndex < 2)
				return null;
			List<Type> parameterTypes = new ArrayList<>();
			for (int index = 2; index < returnIndex; index++) {
				Type parameterType = resolveClassToken(parts[index]);
				if (parameterType == null)
					return null;
				parameterTypes.add(parameterType);
			}
			Type returnType = resolveClassToken(parts[returnIndex]);
			if (returnType == null)
				return null;
			return new DecodedMember(operationName, ownerType.getInternalName(), parts[1], returnType,
					List.copyOf(parameterTypes));
		}

		private boolean initialize(IndyContext context) {
			if (initialized)
				return usable;
			initialized = true;
			try {
				evaluator = context.newEvaluator();
				seedStaticDefaults(context);
				if (evaluator.initialize(classNode.name)) {
					usable = true;
					return true;
				}
				evaluator = context.newEvaluator();
				usable = seedGeneratedCaches(context);
				return usable;
			} catch (RuntimeException ignored) {
				usable = false;
				return false;
			}
		}

		private boolean seedGeneratedCaches(IndyContext context) {
			MethodNode initializer = findMethod(classNode, new MethodKey("<clinit>", "()V"));
			if (initializer == null)
				return false;
			seedStaticDefaults(context);
			MethodNode initializerEntry = createValueReturningMethod(initializer);
			if (initializerEntry != null && evaluator.run(classNode, initializerEntry))
				return true;
			evaluator = context.newEvaluator();
			seedStaticDefaults(context);
			MethodNode prefix = createCachePrefix(initializer, Set.of(metadata.objectField, metadata.descriptorField));
			if (prefix == null || !evaluator.run(classNode, prefix))
				return false;
			MethodNode seed = findMethod(classNode, metadata.seedMethod);
			MethodNode seedEntry = seed == null ? null : createValueReturningMethod(seed);
			if (seedEntry == null)
				return false;
			return evaluator.run(classNode, seedEntry);
		}

		private void seedStaticDefaults(IndyContext context) {
			Set<FieldKey> fields = new HashSet<>();
			for (MethodNode method : classNode.methods) {
				if (method.instructions == null)
					continue;
				for (AbstractInsnNode instruction : method.instructions)
					if (instruction instanceof FieldInsnNode field && instruction.getOpcode() == GETSTATIC
							&& isPrimitiveDescriptor(field.desc))
						fields.add(new FieldKey(field.owner, field.name, field.desc));
			}
			for (FieldKey field : fields) {
				if (evaluator.getStatic(field.owner, field.name, field.descriptor) != null)
					continue;
				ClassNode owner = context.findClass(field.owner);
				FieldNode member = owner == null ? null : findField(owner, field.name, field.descriptor);
				Object constant = member == null ? null : member.value;
				evaluator.putStatic(field.owner, field.name, field.descriptor, primitiveDefault(Type.getType(field.descriptor), constant));
			}
		}

		private static Object primitiveDefault(Type type, Object constant) {
			Number number = constant instanceof Number ? (Number) constant : null;
			switch (type.getSort()) {
				case Type.BOOLEAN:
					return number != null && number.intValue() != 0;
				case Type.CHAR:
					return number == null ? (char) 0 : (char) number.intValue();
				case Type.BYTE:
					return number == null ? (byte) 0 : number.byteValue();
				case Type.SHORT:
					return number == null ? (short) 0 : number.shortValue();
				case Type.INT:
					return number == null ? 0 : number.intValue();
				case Type.LONG:
					return number == null ? 0L : number.longValue();
				case Type.FLOAT:
					return number == null ? 0F : number.floatValue();
				default:
					return number == null ? 0D : number.doubleValue();
			}
		}

		private static boolean isPrimitiveDescriptor(String descriptor) {
			return descriptor.length() == 1
					&& descriptor.charAt(0) != 'V'
					&& descriptor.charAt(0) != 'L'
					&& descriptor.charAt(0) != '[';
		}

		private Integer evaluateIndex(MethodNode indexMethod, long[] metadataKeys) {
			try {
				Object result = metadataKeys.length == 1
						? evaluator.execute(classNode, indexMethod, metadataKeys[0])
						: evaluator.execute(classNode, indexMethod, metadataKeys[0], metadataKeys[1]);
				return result instanceof Number ? ((Number) result).intValue() : null;
			} catch (Throwable ignored) {
				return null;
			}
		}

		private String readString(FieldKey field, int slot) {
			Object value = evaluator.getStatic(field.owner, field.name, field.descriptor);
			if (!(value instanceof Object[]) || slot < 0 || slot >= ((Object[]) value).length)
				return null;
			Object entry = ((Object[]) value)[slot];
			return entry instanceof String ? (String) entry : null;
		}

		private Type resolveClassToken(String token) {
			long key;
			try {
				key = Long.parseLong(token, 36);
			} catch (NumberFormatException ignored) {
				return null;
			}
			if (indexMethod == null)
				return null;
			long[] classTokenKeys = metadata.metadataKeyCount == 1 ? new long[]{key} : new long[]{key, 0L};
			Integer slot = evaluateIndex(indexMethod, classTokenKeys);
			if (slot == null || slot < 0)
				return null;
			Type direct = metadata.classTypes.get(slot);
			return direct != null ? direct : parseClassName(readString(metadata.descriptorField, slot));
		}
	}

	record MethodKey(String name, String descriptor) {}

	
	record FieldKey(String owner, String name, String descriptor) {}

	
	record CleanupMetadata(Set<MethodKey> helperMethods,
	                       Set<FieldKey> helperFields,
	                       Set<FieldKey> stateFields,
	                       FieldKey objectField,
	                       FieldKey descriptorField) {}

	
	private record PreparedSite(String helperOwner,
	                            ClassNode helperNode,
	                            Metadata metadata,
	                            int metadataKeyCount) {}

	
	private record Metadata(MethodKey indexMethod,
	                        int metadataKeyCount,
	                        FieldKey objectField,
	                        FieldKey descriptorField,
	                        MethodKey seedMethod,
	                        Map<Integer, Type> classTypes,
	                        OperationCodes operationCodes) {}

	
	private record OperationCodes(int getField, int putField, int getStatic, int putStatic,
	                              int invokeVirtual, int invokeStatic) {
		private static final OperationCodes EMPTY = new OperationCodes(-1, -1, -1, -1, -1, -1);

		private boolean hasKnownOperation() {
			return getField >= 0 || putField >= 0 || getStatic >= 0 || putStatic >= 0 || invokeVirtual >= 0 || invokeStatic >= 0;
		}
	}

	
	private record DecodedMember(String operationName,
	                             String owner,
	                             String memberName,
	                             Type returnType,
	                             List<Type> parameterTypes) {}

	
	private record FieldMatch(String ownerName,
	                          ClassNode ownerInfo,
	                          FieldNode member) {}

	
	private record MethodMatch(String ownerName,
	                           ClassNode ownerInfo,
	                           MethodNode member) {}
}

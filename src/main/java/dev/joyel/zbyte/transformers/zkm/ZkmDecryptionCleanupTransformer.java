package dev.joyel.zbyte.transformers.zkm;

import dev.joyel.zbyte.analysis.AnalysisSupport;
import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.StaticValueCollectionTransformer;
import dev.joyel.zbyte.transformers.universal.UniversalSupport;
import static dev.joyel.zbyte.transformers.universal.OpaqueConstantFoldingTransformer.toInsn;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public class ZkmDecryptionCleanupTransformer extends Transformer<TransformerConfig> {


	private Map<String, ZkmInvokeDynamicResolver.CleanupMetadata> initialMetadata = Map.of();
	private Set<ZkmInvokeDynamicResolver.FieldKey> initialStateFields = Set.of();

	@Override
	public boolean transform() throws Throwable {
		Map<String, ZkmInvokeDynamicResolver.CleanupMetadata> metadata = ZkmInvokeDynamicResolver.snapshot(classes);
		if (metadata == null) {
			metadata = new HashMap<>();
			for (ClassNode node : classNodes()) {
				ZkmInvokeDynamicResolver.CleanupMetadata value = ZkmInvokeDynamicResolver.discoverCleanupMetadata(node);
				if (value != null)
					metadata.put(node.name, value);
			}
		}
		initialMetadata = Map.copyOf(metadata);
		Set<ZkmInvokeDynamicResolver.FieldKey> stateFields = new HashSet<>();
		for (ZkmInvokeDynamicResolver.CleanupMetadata value : metadata.values())
			stateFields.addAll(value.stateFields());
		initialStateFields = Set.copyOf(stateFields);

		AnalysisSupport support = new AnalysisSupport(classes, classpath);
		StaticValueCollectionTransformer collector = new StaticValueCollectionTransformer();
		collector.prepare(support);
		for (ClassNode node : new ArrayList<>(classNodes()))
			collector.collect(node);

		boolean changed = normalizeStateAccesses(initialStateFields);
		for (ClassNode node : new ArrayList<>(classNodes()))
			changed |= cleanClass(node, collector);
		return changed;
	}

	private boolean cleanClass(ClassNode node, StaticValueCollectionTransformer collector) {
		Map<String, ClassNode> nodes = classes;
		boolean changed = removeUnreferencedBootstrapMethods(node, nodes);

		ZkmInvokeDynamicResolver.CleanupMetadata metadata = initialMetadata.get(node.name);
		if (metadata == null)
			metadata = ZkmInvokeDynamicResolver.discoverCleanupMetadata(node);
		if (metadata == null || !hasValidCandidates(node, metadata))
			return changed;

		if (hasRemainingZkmSite(nodes, node.name))
			return changed;

		if (hasExternalReference(nodes, node.name, metadata, initialMetadata)) {
			if (removeUnreachableHelperMethods(node, metadata, nodes))
				changed = true;
			return changed;
		}

		Set<ZkmInvokeDynamicResolver.FieldKey> removableStateFields = removableStateFields(nodes, metadata, initialMetadata);
		MethodNode initializer = findMethod(node, "<clinit>", "()V");
		List<FieldInitialization> retainedInitializers = new ArrayList<>();
		if (initializer != null) {
			if (!collectRetainedInitializers(nodes, node, initializer, metadata, removableStateFields, collector, retainedInitializers)
					|| !isSupportedInitializer(initializer, node.name, metadata))
				return changed;
		}

		if (initializer != null) {
			if (retainedInitializers.isEmpty()) {
				node.methods.remove(initializer);
			} else {
				InsnList replacement = new InsnList();
				for (FieldInitialization field : retainedInitializers) {
					replacement.add(field.valueInstruction());
					replacement.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, field.name(), field.descriptor()));
				}
				replacement.add(new InsnNode(Opcodes.RETURN));
				initializer.instructions = replacement;
				initializer.tryCatchBlocks.clear();
				initializer.localVariables.clear();
				initializer.maxStack = 2;
				initializer.maxLocals = 0;
			}
		}

		Set<ZkmInvokeDynamicResolver.MethodKey> helperMethods = metadata.helperMethods();
		Set<ZkmInvokeDynamicResolver.FieldKey> helperFields = metadata.helperFields();
		node.methods.removeIf(method -> helperMethods.contains(new ZkmInvokeDynamicResolver.MethodKey(method.name, method.desc)));
		node.fields.removeIf(field -> {
			ZkmInvokeDynamicResolver.FieldKey key = new ZkmInvokeDynamicResolver.FieldKey(node.name, field.name, field.desc);
			boolean generatedString = isUnreferencedGeneratedStringField(nodes, node.name, field, helperMethods);
			boolean generatedPrimitive = isUnreferencedGeneratedPrimitiveField(nodes, node.name, field, helperMethods);
			return helperFields.contains(key) || removableStateFields.contains(key) || generatedString || generatedPrimitive;
		});

		if (!initialStateFields.isEmpty())
			normalizeStateAccesses(initialStateFields);
		return true;
	}

	private static boolean rewriteStateAccesses(ClassNode node,
	                                            Set<ZkmInvokeDynamicResolver.FieldKey> stateFields,
	                                            Map<String, ZkmInvokeDynamicResolver.CleanupMetadata> allMetadata) {
		boolean dirty = false;
		for (MethodNode method : node.methods) {
			if (isComponentMethod(node.name, method, allMetadata) || method.instructions == null)
				continue;

			Map<ZkmInvokeDynamicResolver.FieldKey, AbstractInsnNode> known = new HashMap<>();
			for (ZkmInvokeDynamicResolver.FieldKey state : stateFields) {
				AbstractInsnNode defaultValue = defaultStateInstruction(state.descriptor());
				if (defaultValue != null)
					known.put(state, defaultValue);
			}

			for (AbstractInsnNode instruction : method.instructions.toArray()) {
				if (instruction instanceof JumpInsnNode
						|| instruction instanceof TableSwitchInsnNode
						|| instruction instanceof LookupSwitchInsnNode) {
					known.clear();
					continue;
				}

				if (!(instruction instanceof FieldInsnNode field))
					continue;

				ZkmInvokeDynamicResolver.FieldKey key = new ZkmInvokeDynamicResolver.FieldKey(field.owner, field.name, field.desc);
				if (!stateFields.contains(key))
					continue;

				if (field.getOpcode() == Opcodes.GETSTATIC) {
					AbstractInsnNode knownValue = known.get(key);
					if (knownValue == null)
						continue;
					method.instructions.set(field, knownValue.clone(Map.of()));
					dirty = true;
				} else if (field.getOpcode() == Opcodes.PUTSTATIC) {
					AbstractInsnNode producer = getPreviousInsn(field);
					AbstractInsnNode constant = constantStateInstruction(producer, field.desc);
					if (constant == null)
						known.remove(key);
					else
						known.put(key, constant);
				}
			}
		}
		return dirty;
	}

	
	private static boolean removeUnobservedStateWrites(ClassNode node,
	                                                   Set<ZkmInvokeDynamicResolver.FieldKey> stateFields,
	                                                   Set<ZkmInvokeDynamicResolver.FieldKey> survivingReads,
	                                                   Map<String, ZkmInvokeDynamicResolver.CleanupMetadata> allMetadata) {
		boolean dirty = false;
		for (MethodNode method : node.methods) {
			if (isComponentMethod(node.name, method, allMetadata) || method.instructions == null)
				continue;

			for (AbstractInsnNode instruction : method.instructions.toArray()) {
				if (instruction.getOpcode() != Opcodes.PUTSTATIC || !(instruction instanceof FieldInsnNode field))
					continue;
				ZkmInvokeDynamicResolver.FieldKey key = new ZkmInvokeDynamicResolver.FieldKey(field.owner, field.name, field.desc);
				if (stateFields.contains(key) && !survivingReads.contains(key)) {
					method.instructions.set(field, new InsnNode(isWide(field.desc) ? Opcodes.POP2 : Opcodes.POP));
					dirty = true;
				}
			}
		}
		return dirty;
	}

	
	private static AbstractInsnNode constantStateInstruction(AbstractInsnNode producer, String descriptor) {
		if (producer == null)
			return null;

		int sort;
		try {
			sort = Type.getType(descriptor).getSort();
		} catch (Throwable ignored) {
			return null;
		}

		int opcode = producer.getOpcode();
		if (sort == Type.BOOLEAN || sort == Type.BYTE || sort == Type.CHAR || sort == Type.SHORT || sort == Type.INT) {
			if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5 || opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)
				return producer;
			return producer instanceof LdcInsnNode ldc && ldc.cst instanceof Integer ? producer : null;
		}
		if (sort == Type.FLOAT) {
			if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2)
				return producer;
			return producer instanceof LdcInsnNode ldc && ldc.cst instanceof Float ? producer : null;
		}
		if (sort == Type.LONG) {
			if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1)
				return producer;
			return producer instanceof LdcInsnNode ldc && ldc.cst instanceof Long ? producer : null;
		}
		if (sort == Type.DOUBLE) {
			if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1)
				return producer;
			return producer instanceof LdcInsnNode ldc && ldc.cst instanceof Double ? producer : null;
		}
		return null;
	}

	
	private static AbstractInsnNode defaultStateInstruction(String descriptor) {
		try {
			return switch (Type.getType(descriptor).getSort()) {
				case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> UniversalSupport.constantInsn(0);
				case Type.FLOAT -> UniversalSupport.constantInsn(0F);
				case Type.LONG -> UniversalSupport.constantInsn(0L);
				case Type.DOUBLE -> UniversalSupport.constantInsn(0D);
				default -> null;
			};
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	
	private boolean normalizeStateAccesses(Set<ZkmInvokeDynamicResolver.FieldKey> stateFields) {
		if (stateFields.isEmpty())
			return false;

		Map<String, ClassNode> nodes = classes;
		boolean dirty = false;
		for (Map.Entry<String, ClassNode> entry : nodes.entrySet())
			dirty |= rewriteStateAccesses(entry.getValue(), stateFields, initialMetadata);

		Set<ZkmInvokeDynamicResolver.FieldKey> survivingReads = new HashSet<>();
		for (Map.Entry<String, ClassNode> entry : nodes.entrySet()) {
			ClassNode node = entry.getValue();
			for (MethodNode method : node.methods) {
				if (isComponentMethod(node.name, method, initialMetadata) || method.instructions == null)
					continue;
				for (AbstractInsnNode instruction : method.instructions) {
					if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC)
						continue;
					ZkmInvokeDynamicResolver.FieldKey key = new ZkmInvokeDynamicResolver.FieldKey(field.owner, field.name, field.desc);
					if (stateFields.contains(key))
						survivingReads.add(key);
				}
			}
		}

		for (Map.Entry<String, ClassNode> entry : nodes.entrySet())
			dirty |= removeUnobservedStateWrites(entry.getValue(), stateFields, survivingReads, initialMetadata);
		return dirty;
	}

	private static boolean removeUnreferencedBootstrapMethods(ClassNode node,
	                                                          Map<String, ClassNode> nodes) {
		boolean dirty = false;
		for (MethodNode method : List.copyOf(node.methods)) {
			if (!isBootstrapCandidate(method)
					|| hasMethodReference(nodes, node.name, method.name, method.desc))
				continue;
			node.methods.remove(method);
			dirty = true;
		}
		return dirty;
	}

	
	private static boolean isBootstrapCandidate(MethodNode method) {
		return (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)
				&& ZkmInvokeDynamicResolver.BOOTSTRAP_DESCRIPTOR.equals(method.desc)
				&& !"<init>".equals(method.name) && !"<clinit>".equals(method.name);
	}

	
	private static boolean hasMethodReference(Map<String, ClassNode> nodes, String owner,
	                                          String name, String descriptor) {
		Set<ZkmInvokeDynamicResolver.MethodKey> methods = Set.of(new ZkmInvokeDynamicResolver.MethodKey(name, descriptor));
		Set<ZkmInvokeDynamicResolver.FieldKey> fields = Set.of();
		for (ClassNode node : nodes.values()) {
			for (MethodNode method : node.methods) {
				if (containsComponentAnnotations(method, owner, methods, fields))
					return true;

				if (method.instructions == null)
					continue;
				for (AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof MethodInsnNode call
							&& owner.equals(call.owner)
							&& name.equals(call.name)
							&& descriptor.equals(call.desc))
						return true;
					if (instruction instanceof InvokeDynamicInsnNode indy
							&& (containsComponentValue(indy.bsm, owner, methods, fields)
							|| containsComponentValues(indy.bsmArgs, owner, methods, fields)))
						return true;
					if (instruction instanceof LdcInsnNode ldc && containsComponentValue(ldc.cst, owner, methods, fields))
						return true;
				}
			}

			if (containsComponentClassMetadata(node, owner, methods, fields))
				return true;
		}
		return false;
	}

	
	private static boolean hasValidCandidates(ClassNode node,
	                                          ZkmInvokeDynamicResolver.CleanupMetadata metadata) {
		boolean found = false;
		for (ZkmInvokeDynamicResolver.MethodKey key : metadata.helperMethods()) {
			MethodNode method = findMethod(node, key.name(), key.descriptor());
			if (method == null)
				continue;

			found = true;
			if ((method.access & Opcodes.ACC_STATIC) == 0
					|| (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
					|| "<init>".equals(method.name) || "<clinit>".equals(method.name))
				return false;
		}
		for (ZkmInvokeDynamicResolver.FieldKey key : metadata.helperFields()) {
			if (!node.name.equals(key.owner()))
				return false;

			FieldNode field = findField(node, key.name(), key.descriptor());
			if (field == null)
				continue;
			found = true;

			if ((field.access & Opcodes.ACC_STATIC) == 0)
				return false;
		}
		return found;
	}

	
	private static boolean hasRemainingZkmSite(Map<String, ClassNode> nodes, String owner) {
		for (ClassNode node : nodes.values()) {
			for (MethodNode method : node.methods) {
				if (method.instructions == null)
					continue;
				for (AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof InvokeDynamicInsnNode indy
							&& indy.bsm != null
							&& owner.equals(indy.bsm.getOwner())
							&& ZkmInvokeDynamicResolver.BOOTSTRAP_DESCRIPTOR.equals(indy.bsm.getDesc()))
						return true;
				}
			}
		}
		return false;
	}

	
	private static boolean hasExternalReference(Map<String, ClassNode> nodes, String targetOwner,
	                                            ZkmInvokeDynamicResolver.CleanupMetadata metadata,
	                                            Map<String, ZkmInvokeDynamicResolver.CleanupMetadata> allMetadata) {
		Set<ZkmInvokeDynamicResolver.MethodKey> helpers = metadata.helperMethods();
		Set<ZkmInvokeDynamicResolver.FieldKey> fields = new HashSet<>(metadata.helperFields());
		fields.addAll(metadata.stateFields());
		for (Map.Entry<String, ClassNode> entry : nodes.entrySet()) {
			String callerOwner = entry.getKey();
			ClassNode node = entry.getValue();
			for (MethodNode method : node.methods) {
				boolean helperCaller = isComponentMethod(callerOwner, method, allMetadata);
				if (helperCaller)
					continue;

				if (containsComponentAnnotations(method, targetOwner, helpers, fields))
					return true;

				if (method.instructions == null)
					continue;
				for (AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof MethodInsnNode call && targetOwner.equals(call.owner)
							&& helpers.contains(new ZkmInvokeDynamicResolver.MethodKey(call.name, call.desc)))
						return true;
					if (instruction instanceof FieldInsnNode field && targetOwner.equals(field.owner)) {
						ZkmInvokeDynamicResolver.FieldKey key = new ZkmInvokeDynamicResolver.FieldKey(field.owner, field.name, field.desc);
						if (fields.contains(key)
								&& (field.getOpcode() != Opcodes.PUTSTATIC
								|| !metadata.stateFields().contains(key)))
							return true;
					}
					if (instruction instanceof InvokeDynamicInsnNode indy
							&& (containsComponentValue(indy.bsm, targetOwner, helpers, fields)
							|| containsComponentValues(indy.bsmArgs, targetOwner, helpers, fields)))
						return true;
					if (instruction instanceof LdcInsnNode ldc && containsComponentValue(ldc.cst, targetOwner, helpers, fields))
						return true;
				}
			}
			if (containsComponentClassMetadata(node, targetOwner, helpers, fields))
				return true;
		}
		return false;
	}

	
	private static boolean isComponentMethod(String owner, MethodNode method,
	                                         Map<String, ZkmInvokeDynamicResolver.CleanupMetadata> allMetadata) {
		ZkmInvokeDynamicResolver.CleanupMetadata metadata = allMetadata.get(owner);
		if (metadata == null)
			return false;
		return "<clinit>".equals(method.name)
				|| metadata.helperMethods().contains(new ZkmInvokeDynamicResolver.MethodKey(method.name, method.desc));
	}

	
	private static boolean removeUnreachableHelperMethods(ClassNode target,
	                                                      ZkmInvokeDynamicResolver.CleanupMetadata metadata,
	                                                      Map<String, ClassNode> nodes) {
		Set<ZkmInvokeDynamicResolver.MethodKey> helpers = metadata.helperMethods();
		Set<ZkmInvokeDynamicResolver.MethodKey> live = new HashSet<>();
		for (Map.Entry<String, ClassNode> entry : nodes.entrySet()) {
			String owner = entry.getKey();

			for (MethodNode method : entry.getValue().methods) {
				ZkmInvokeDynamicResolver.MethodKey key = new ZkmInvokeDynamicResolver.MethodKey(method.name, method.desc);
				boolean generated = target.name.equals(owner) && helpers.contains(key);
				if (generated && !"<clinit>".equals(method.name))
					continue;

				collectHelperCalls(method, target.name, helpers, live);
			}
		}

		Set<ZkmInvokeDynamicResolver.MethodKey> reachable = new HashSet<>(live);
		boolean changed;
		do {
			changed = false;
			for (ZkmInvokeDynamicResolver.MethodKey key : List.copyOf(reachable)) {
				MethodNode method = findMethod(target, key.name(), key.descriptor());
				if (method == null)
					continue;

				int before = reachable.size();
				collectHelperCalls(method, target.name, helpers, reachable);
				changed |= before != reachable.size();
			}
		} while (changed);

		Set<ZkmInvokeDynamicResolver.MethodKey> removable = new HashSet<>(helpers);
		removable.removeAll(reachable);
		if (removable.isEmpty())
			return false;
		return target.methods.removeIf(method -> removable.contains(new ZkmInvokeDynamicResolver.MethodKey(method.name, method.desc)));
	}

	
	private static void collectHelperCalls(MethodNode method, String owner,
	                                       Set<ZkmInvokeDynamicResolver.MethodKey> helpers,
	                                       Set<ZkmInvokeDynamicResolver.MethodKey> output) {
		if (method.instructions == null)
			return;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)) {
				ZkmInvokeDynamicResolver.MethodKey key = new ZkmInvokeDynamicResolver.MethodKey(call.name, call.desc);
				if (helpers.contains(key))
					output.add(key);
			} else if (instruction instanceof InvokeDynamicInsnNode indy) {
				collectHelperHandle(indy.bsm, owner, helpers, output);
				collectHelperValues(indy.bsmArgs, owner, helpers, output);
			} else if (instruction instanceof LdcInsnNode ldc) {
				collectHelperValue(ldc.cst, owner, helpers, output);
			}
		}
	}

	
	private static void collectHelperValues(Object[] values, String owner,
	                                        Set<ZkmInvokeDynamicResolver.MethodKey> helpers,
	                                        Set<ZkmInvokeDynamicResolver.MethodKey> output) {
		if (values == null)
			return;
		for (Object value : values)
			collectHelperValue(value, owner, helpers, output);
	}

	private static void collectHelperValue(Object value, String owner,
	                                       Set<ZkmInvokeDynamicResolver.MethodKey> helpers,
	                                       Set<ZkmInvokeDynamicResolver.MethodKey> output) {
		if (value instanceof Handle handle)
			collectHelperHandle(handle, owner, helpers, output);
		else if (value instanceof AnnotationNode annotation)
			collectHelperValue(annotation.values, owner, helpers, output);
		else if (value instanceof ConstantDynamic dynamic) {
			collectHelperHandle(dynamic.getBootstrapMethod(), owner, helpers, output);
			for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++)
				collectHelperValue(dynamic.getBootstrapMethodArgument(index), owner, helpers, output);
		} else if (value instanceof Iterable<?> values)
			for (Object nested : values)
				collectHelperValue(nested, owner, helpers, output);
		else if (value instanceof Object[] values)
			for (Object nested : values)
				collectHelperValue(nested, owner, helpers, output);
	}

	private static void collectHelperHandle(Handle handle, String owner,
	                                        Set<ZkmInvokeDynamicResolver.MethodKey> helpers,
	                                        Set<ZkmInvokeDynamicResolver.MethodKey> output) {
		if (handle == null || !owner.equals(handle.getOwner()))
			return;
		ZkmInvokeDynamicResolver.MethodKey key = new ZkmInvokeDynamicResolver.MethodKey(handle.getName(), handle.getDesc());
		if (helpers.contains(key))
			output.add(key);
	}

	
	private static Set<ZkmInvokeDynamicResolver.FieldKey> removableStateFields(Map<String, ClassNode> nodes,
	                                                                           ZkmInvokeDynamicResolver.CleanupMetadata metadata,
	                                                                           Map<String, ZkmInvokeDynamicResolver.CleanupMetadata> allMetadata) {
		Set<ZkmInvokeDynamicResolver.FieldKey> result = new HashSet<>();
		for (ZkmInvokeDynamicResolver.FieldKey state : metadata.stateFields())
			if (!hasExternalStateReference(nodes, state, allMetadata))
				result.add(state);
		return result;
	}

	
	private static boolean hasExternalStateReference(Map<String, ClassNode> nodes,
	                                                 ZkmInvokeDynamicResolver.FieldKey target,
	                                                 Map<String, ZkmInvokeDynamicResolver.CleanupMetadata> allMetadata) {
		for (Map.Entry<String, ClassNode> entry : nodes.entrySet()) {
			String callerOwner = entry.getKey();
			ClassNode node = entry.getValue();
			for (MethodNode method : node.methods) {
				boolean helperCaller = isComponentMethod(callerOwner, method, allMetadata);
				if (helperCaller)
					continue;

				if (method.instructions != null)
					for (AbstractInsnNode instruction : method.instructions) {
						if (instruction instanceof FieldInsnNode field && target.equals(new ZkmInvokeDynamicResolver.FieldKey(field.owner, field.name, field.desc)))
							return true;
						if (instruction instanceof InvokeDynamicInsnNode indy
								&& (containsStateFieldHandle(indy.bsm, target)
								|| containsStateFieldValues(indy.bsmArgs, target)))
							return true;
						if (instruction instanceof LdcInsnNode ldc && containsStateFieldValue(ldc.cst, target))
							return true;
					}

				if (containsStateFieldMethodMetadata(method, target))
					return true;
			}
			if (containsStateFieldClassMetadata(node, target))
				return true;
		}
		return false;
	}

	
	private static boolean isUnreferencedGeneratedStringField(Map<String, ClassNode> nodes,
	                                                          String targetOwner, FieldNode field,
	                                                          Set<ZkmInvokeDynamicResolver.MethodKey> helpers) {
		if (!"Ljava/lang/String;".equals(field.desc)
				|| (field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
				!= (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
				|| field.value != null)
			return false;

		ClassNode owner = nodes.get(targetOwner);
		if (owner == null || !hasNonPrivateState(owner))
			return false;

		ZkmInvokeDynamicResolver.FieldKey target = new ZkmInvokeDynamicResolver.FieldKey(targetOwner, field.name, field.desc);
		for (Map.Entry<String, ClassNode> entry : nodes.entrySet()) {
			String callerOwner = entry.getKey();
			for (MethodNode method : entry.getValue().methods) {
				if (method.instructions == null)
					continue;
				boolean helperCaller = targetOwner.equals(callerOwner)
						&& helpers.contains(new ZkmInvokeDynamicResolver.MethodKey(method.name, method.desc));
				for (AbstractInsnNode instruction : method.instructions) {
					if (!(instruction instanceof FieldInsnNode access)
							|| !target.equals(new ZkmInvokeDynamicResolver.FieldKey(access.owner, access.name, access.desc)))
						continue;

					if (targetOwner.equals(callerOwner)
							&& "<clinit>".equals(method.name)
							&& access.getOpcode() == Opcodes.PUTSTATIC)
						continue;

					if (!helperCaller)
						return false;
				}
				if (!helperCaller && containsStateFieldMethodMetadata(method, target))
					return false;
			}
			if (containsStateFieldClassMetadata(entry.getValue(), target))
				return false;
		}
		return true;
	}

	
	private static boolean isUnreferencedGeneratedPrimitiveField(Map<String, ClassNode> nodes,
	                                                             String targetOwner, FieldNode field,
	                                                             Set<ZkmInvokeDynamicResolver.MethodKey> helpers) {
		if ((field.access & Opcodes.ACC_STATIC) == 0 || !isPrimitive(field.desc) || field.value != null)
			return false;

		ZkmInvokeDynamicResolver.FieldKey target = new ZkmInvokeDynamicResolver.FieldKey(targetOwner, field.name, field.desc);
		for (Map.Entry<String, ClassNode> entry : nodes.entrySet()) {
			String callerOwner = entry.getKey();
			for (MethodNode method : entry.getValue().methods) {
				if (method.instructions == null)
					continue;
				boolean helperCaller = targetOwner.equals(callerOwner)
						&& ("<clinit>".equals(method.name)
						|| helpers.contains(new ZkmInvokeDynamicResolver.MethodKey(method.name, method.desc)));
				for (AbstractInsnNode instruction : method.instructions)
					if (instruction instanceof FieldInsnNode access
							&& target.equals(new ZkmInvokeDynamicResolver.FieldKey(access.owner, access.name, access.desc))
							&& !helperCaller)
						return false;
				if (!helperCaller && containsStateFieldMethodMetadata(method, target))
					return false;
			}
			if (containsStateFieldClassMetadata(entry.getValue(), target))
				return false;
		}
		return true;
	}

	
	private static boolean hasNonPrivateState(ClassNode node) {
		for (FieldNode field : node.fields)
			if ((field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == Opcodes.ACC_STATIC
					&& isPrimitive(field.desc))
				return true;
		return false;
	}

	
	private static boolean collectRetainedInitializers(Map<String, ClassNode> nodes,
	                                                   ClassNode node, MethodNode initializer,
	                                                   ZkmInvokeDynamicResolver.CleanupMetadata metadata,
	                                                   Set<ZkmInvokeDynamicResolver.FieldKey> removableStateFields,
	                                                   StaticValueCollectionTransformer collector,
	                                                   List<FieldInitialization> retained) {
		if (initializer.instructions == null)
			return true;

		Set<ZkmInvokeDynamicResolver.MethodKey> helperMethods = metadata.helperMethods();
		Set<ZkmInvokeDynamicResolver.FieldKey> helperFields = metadata.helperFields();
		Set<ZkmInvokeDynamicResolver.FieldKey> seen = new HashSet<>();
		for (AbstractInsnNode instruction : initializer.instructions) {
			if (!(instruction instanceof FieldInsnNode field))
				continue;
			if (!node.name.equals(field.owner))
				return false;
			ZkmInvokeDynamicResolver.FieldKey key = new ZkmInvokeDynamicResolver.FieldKey(field.owner, field.name, field.desc);
			if (field.getOpcode() == Opcodes.PUTSTATIC) {
				if (helperFields.contains(key) || removableStateFields.contains(key))
					continue;

				if (canDropUnobservedInitializerStore(nodes, node.name, key, helperMethods))
					continue;

				if (!seen.add(key) || !isStatic(node, field.name, field.desc) || collector == null)
					return false;
				ReValue value = collector.getStaticValue(field.owner, field.name, field.desc);
				AbstractInsnNode valueInstruction = toInsn(value);
				if (valueInstruction == null)
					return false;
				retained.add(new FieldInitialization(field.name, field.desc, valueInstruction));
			} else if (field.getOpcode() != Opcodes.GETSTATIC || (!helperFields.contains(key) && !removableStateFields.contains(key)))
				return false;
		}
		return true;
	}

	
	private static boolean canDropUnobservedInitializerStore(Map<String, ClassNode> nodes,
	                                                         String targetOwner,
	                                                         ZkmInvokeDynamicResolver.FieldKey target,
	                                                         Set<ZkmInvokeDynamicResolver.MethodKey> helpers) {
		ClassNode owner = nodes.get(targetOwner);
		if (owner == null)
			return false;

		FieldNode declaration = findField(owner, target.name(), target.descriptor());
		if (declaration == null
				|| (declaration.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
				|| !"Ljava/lang/String;".equals(target.descriptor()))
			return false;

		for (Map.Entry<String, ClassNode> entry : nodes.entrySet()) {
			String callerOwner = entry.getKey();
			for (MethodNode method : entry.getValue().methods) {
				if (method.instructions == null)
					continue;

				boolean helperCaller = targetOwner.equals(callerOwner)
						&& ("<clinit>".equals(method.name)
						|| helpers.contains(new ZkmInvokeDynamicResolver.MethodKey(method.name, method.desc)));
				if (helperCaller)
					continue;

				for (AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof FieldInsnNode field && target.equals(new ZkmInvokeDynamicResolver.FieldKey(field.owner, field.name, field.desc)))
						return false;
					if (instruction instanceof InvokeDynamicInsnNode dynamic
							&& (containsStateFieldHandle(dynamic.bsm, target)
							|| containsStateFieldValues(dynamic.bsmArgs, target)))
						return false;
					if (instruction instanceof LdcInsnNode ldc && containsStateFieldValue(ldc.cst, target))
						return false;
				}
				if (containsStateFieldMethodMetadata(method, target))
					return false;
			}
			if (containsStateFieldClassMetadata(entry.getValue(), target))
				return false;
		}
		return true;
	}

	
	private static boolean isSupportedInitializer(MethodNode initializer, String owner,
	                                              ZkmInvokeDynamicResolver.CleanupMetadata metadata) {
		Set<ZkmInvokeDynamicResolver.MethodKey> helpers = metadata.helperMethods();
		if (initializer.instructions == null)
			return true;
		for (AbstractInsnNode instruction : initializer.instructions) {
			int opcode = instruction.getOpcode();
			if (opcode == Opcodes.MONITORENTER
					|| opcode == Opcodes.MONITOREXIT
					|| opcode == Opcodes.JSR
					|| opcode == Opcodes.RET
					|| opcode == Opcodes.ATHROW)
				return false;

			if (instruction instanceof InvokeDynamicInsnNode)
				return false;

			if (instruction instanceof MethodInsnNode call) {
				if (owner.equals(call.owner) && helpers.contains(new ZkmInvokeDynamicResolver.MethodKey(call.name, call.desc)))
					continue;
				if (!isWhitelistedPoolCall(call))
					return false;
			}

			if (instruction instanceof TypeInsnNode type
					&& opcode == Opcodes.NEW
					&& !"java/lang/String".equals(type.desc))
				return false;
		}
		return true;
	}

	
	private static boolean isWhitelistedPoolCall(MethodInsnNode call) {
		if ("java/lang/String".equals(call.owner))
			return call.name.equals("<init>")
					|| call.name.equals("length")
					|| call.name.equals("substring")
					|| call.name.equals("charAt")
					|| call.name.equals("toCharArray")
					|| call.name.equals("getBytes")
					|| call.name.equals("intern");
		return call.name.equals("valueOf") && call.owner.startsWith("java/lang/")
				&& Type.getReturnType(call.desc).getSort() == Type.OBJECT;
	}

	
	private static boolean isStatic(ClassNode node, String name, String descriptor) {
		FieldNode field = findField(node, name, descriptor);
		return field != null && (field.access & Opcodes.ACC_STATIC) != 0;
	}

	private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
		for (MethodNode method : node.methods)
			if (name.equals(method.name) && descriptor.equals(method.desc))
				return method;
		return null;
	}

	private static FieldNode findField(ClassNode node, String name, String descriptor) {
		for (FieldNode field : node.fields)
			if (name.equals(field.name) && descriptor.equals(field.desc))
				return field;
		return null;
	}

	
	private static boolean containsComponentAnnotations(MethodNode method, String owner,
	                                                    Set<ZkmInvokeDynamicResolver.MethodKey> methods,
	                                                    Set<ZkmInvokeDynamicResolver.FieldKey> fields) {
		return containsComponentAnnotations(method.visibleAnnotations, owner, methods, fields)
				|| containsComponentAnnotations(method.invisibleAnnotations, owner, methods, fields)
				|| containsComponentAnnotations(method.visibleTypeAnnotations, owner, methods, fields)
				|| containsComponentAnnotations(method.invisibleTypeAnnotations, owner, methods, fields)
				|| containsComponentValue(method.annotationDefault, owner, methods, fields)
				|| containsComponentValue(method.visibleParameterAnnotations, owner, methods, fields)
				|| containsComponentValue(method.invisibleParameterAnnotations, owner, methods, fields)
				|| containsComponentValue(method.visibleLocalVariableAnnotations, owner, methods, fields)
				|| containsComponentValue(method.invisibleLocalVariableAnnotations, owner, methods, fields);
	}

	
	private static boolean containsComponentClassMetadata(ClassNode node, String owner,
	                                                      Set<ZkmInvokeDynamicResolver.MethodKey> methods,
	                                                      Set<ZkmInvokeDynamicResolver.FieldKey> fields) {
		if (containsComponentAnnotations(node.visibleAnnotations, owner, methods, fields)
				|| containsComponentAnnotations(node.invisibleAnnotations, owner, methods, fields)
				|| containsComponentAnnotations(node.visibleTypeAnnotations, owner, methods, fields)
				|| containsComponentAnnotations(node.invisibleTypeAnnotations, owner, methods, fields))
			return true;
		for (FieldNode field : node.fields)
			if (containsComponentValue(field.value, owner, methods, fields)
					|| containsComponentAnnotations(field.visibleAnnotations, owner, methods, fields)
					|| containsComponentAnnotations(field.invisibleAnnotations, owner, methods, fields)
					|| containsComponentAnnotations(field.visibleTypeAnnotations, owner, methods, fields)
					|| containsComponentAnnotations(field.invisibleTypeAnnotations, owner, methods, fields))
				return true;
		if (node.recordComponents != null)
			for (RecordComponentNode component : node.recordComponents)
				if (containsComponentAnnotations(component.visibleAnnotations, owner, methods, fields)
						|| containsComponentAnnotations(component.invisibleAnnotations, owner, methods, fields)
						|| containsComponentAnnotations(component.visibleTypeAnnotations, owner, methods, fields)
						|| containsComponentAnnotations(component.invisibleTypeAnnotations, owner, methods, fields))
					return true;
		return false;
	}

	private static boolean containsComponentAnnotations(List<? extends AnnotationNode> annotations,
	                                                    String owner,
	                                                    Set<ZkmInvokeDynamicResolver.MethodKey> methods,
	                                                    Set<ZkmInvokeDynamicResolver.FieldKey> fields) {
		if (annotations == null)
			return false;
		for (AnnotationNode annotation : annotations)
			if (containsComponentValue(annotation.values, owner, methods, fields))
				return true;
		return false;
	}

	private static boolean containsComponentValues(Object[] values, String owner,
	                                               Set<ZkmInvokeDynamicResolver.MethodKey> methods,
	                                               Set<ZkmInvokeDynamicResolver.FieldKey> fields) {
		return containsComponentValue(values, owner, methods, fields);
	}

	
	private static boolean containsComponentValue(Object value, String owner,
	                                              Set<ZkmInvokeDynamicResolver.MethodKey> methods,
	                                              Set<ZkmInvokeDynamicResolver.FieldKey> fields) {
		if (value instanceof Handle handle) {
			if (owner.equals(handle.getOwner())
					&& methods.contains(new ZkmInvokeDynamicResolver.MethodKey(handle.getName(), handle.getDesc())))
				return true;
			return (handle.getTag() == Opcodes.H_GETSTATIC
					|| handle.getTag() == Opcodes.H_PUTSTATIC)
					&& fields.contains(new ZkmInvokeDynamicResolver.FieldKey(handle.getOwner(), handle.getName(), handle.getDesc()));
		}
		if (value instanceof ConstantDynamic dynamic) {
			if (containsComponentValue(dynamic.getBootstrapMethod(), owner, methods, fields))
				return true;
			for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++)
				if (containsComponentValue(dynamic.getBootstrapMethodArgument(index), owner, methods, fields))
					return true;
			return false;
		}
		if (value instanceof AnnotationNode annotation)
			return containsComponentValue(annotation.values, owner, methods, fields);
		if (value instanceof Iterable<?> values)
			for (Object nested : values)
				if (containsComponentValue(nested, owner, methods, fields))
					return true;
		if (value instanceof Object[] values)
			for (Object nested : values)
				if (containsComponentValue(nested, owner, methods, fields))
					return true;
		return false;
	}

	
	private static boolean containsStateFieldMethodMetadata(MethodNode method,
	                                                        ZkmInvokeDynamicResolver.FieldKey target) {
		return containsStateFieldAnnotations(method.visibleAnnotations, target)
				|| containsStateFieldAnnotations(method.invisibleAnnotations, target)
				|| containsStateFieldAnnotations(method.visibleTypeAnnotations, target)
				|| containsStateFieldAnnotations(method.invisibleTypeAnnotations, target)
				|| containsStateFieldAnnotations(method.visibleLocalVariableAnnotations, target)
				|| containsStateFieldAnnotations(method.invisibleLocalVariableAnnotations, target)
				|| containsStateFieldValues(method.visibleParameterAnnotations, target)
				|| containsStateFieldValues(method.invisibleParameterAnnotations, target)
				|| containsStateFieldValue(method.annotationDefault, target);
	}

	
	private static boolean containsStateFieldClassMetadata(ClassNode node,
	                                                       ZkmInvokeDynamicResolver.FieldKey target) {
		if (containsStateFieldAnnotations(node.visibleAnnotations, target)
				|| containsStateFieldAnnotations(node.invisibleAnnotations, target)
				|| containsStateFieldAnnotations(node.visibleTypeAnnotations, target)
				|| containsStateFieldAnnotations(node.invisibleTypeAnnotations, target))
			return true;
		for (FieldNode field : node.fields)
			if (containsStateFieldValue(field.value, target)
					|| containsStateFieldAnnotations(field.visibleAnnotations, target)
					|| containsStateFieldAnnotations(field.invisibleAnnotations, target)
					|| containsStateFieldAnnotations(field.visibleTypeAnnotations, target)
					|| containsStateFieldAnnotations(field.invisibleTypeAnnotations, target))
				return true;
		if (node.recordComponents != null)
			for (RecordComponentNode component : node.recordComponents)
				if (containsStateFieldAnnotations(component.visibleAnnotations, target)
						|| containsStateFieldAnnotations(component.invisibleAnnotations, target)
						|| containsStateFieldAnnotations(component.visibleTypeAnnotations, target)
						|| containsStateFieldAnnotations(component.invisibleTypeAnnotations, target))
					return true;
		return false;
	}

	private static boolean containsStateFieldAnnotations(List<? extends AnnotationNode> annotations,
	                                                     ZkmInvokeDynamicResolver.FieldKey target) {
		if (annotations == null)
			return false;
		for (AnnotationNode annotation : annotations)
			if (containsStateFieldValue(annotation.values, target))
				return true;
		return false;
	}

	private static boolean containsStateFieldValues(Object value,
	                                                ZkmInvokeDynamicResolver.FieldKey target) {
		if (value instanceof Iterable<?> values)
			for (Object nested : values)
				if (containsStateFieldValue(nested, target))
					return true;
		if (value instanceof Object[] values)
			for (Object nested : values)
				if (containsStateFieldValue(nested, target))
					return true;
		return containsStateFieldValue(value, target);
	}

	
	private static boolean containsStateFieldValue(Object value,
	                                               ZkmInvokeDynamicResolver.FieldKey target) {
		if (value instanceof Handle handle)
			return (handle.getTag() == Opcodes.H_GETSTATIC
					|| handle.getTag() == Opcodes.H_PUTSTATIC)
					&& target.equals(new ZkmInvokeDynamicResolver.FieldKey(handle.getOwner(), handle.getName(), handle.getDesc()));
		if (value instanceof AnnotationNode annotation)
			return containsStateFieldValue(annotation.values, target);
		if (value instanceof ConstantDynamic dynamic) {
			if (containsStateFieldValue(dynamic.getBootstrapMethod(), target))
				return true;
			for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++)
				if (containsStateFieldValue(dynamic.getBootstrapMethodArgument(index), target))
					return true;
			return false;
		}
		if (value instanceof Iterable<?> values)
			for (Object nested : values)
				if (containsStateFieldValue(nested, target))
					return true;
		if (value instanceof Object[] values)
			for (Object nested : values)
				if (containsStateFieldValue(nested, target))
					return true;
		return false;
	}

	private static boolean containsStateFieldHandle(Handle handle,
	                                                ZkmInvokeDynamicResolver.FieldKey target) {
		return handle != null && (handle.getTag() == Opcodes.H_GETSTATIC || handle.getTag() == Opcodes.H_PUTSTATIC)
				&& target.equals(new ZkmInvokeDynamicResolver.FieldKey(handle.getOwner(), handle.getName(), handle.getDesc()));
	}

	private record FieldInitialization(String name, String descriptor,
	                                   AbstractInsnNode valueInstruction) {}

	private static AbstractInsnNode getPreviousInsn(AbstractInsnNode instruction) {
		AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
		while (current != null && current.getOpcode() < 0)
			current = current.getPrevious();
		return current;
	}

	private static boolean isWide(String descriptor) {
		return descriptor.equals("J") || descriptor.equals("D");
	}

	private static boolean isPrimitive(String descriptor) {
		return descriptor.length() == 1 && "ZBCSIJFD".indexOf(descriptor.charAt(0)) >= 0;
	}
}

package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import java.util.*;

import dev.joyel.zbyte.reanalysis.AnalysisSupport;
import dev.joyel.zbyte.reanalysis.Nullness;
import dev.joyel.zbyte.reanalysis.ReAnalyzer;
import dev.joyel.zbyte.reanalysis.ReFrame;
import dev.joyel.zbyte.reanalysis.ReInterpreter;
import dev.joyel.zbyte.reanalysis.TransformationException;
import dev.joyel.zbyte.reanalysis.lookup.GetStaticLookup;
import dev.joyel.zbyte.reanalysis.value.ArrayValue;
import dev.joyel.zbyte.reanalysis.value.IllegalValueException;
import dev.joyel.zbyte.reanalysis.value.ReValue;
import dev.joyel.zbyte.reanalysis.value.UninitializedValue;
import dev.joyel.zbyte.reanalysis.value.impl.ArrayValueImpl;
import dev.joyel.zbyte.utils.AsmFlowUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;

import static dev.joyel.zbyte.utils.AsmFlowUtil.*;

public class StaticValueCollectionTransformer extends Transformer<TransformerConfig> implements GetStaticLookup {
    private boolean changed;

    private void markChange() {
        changed = true;
    }

    @Override
    public boolean transform() throws Throwable {
        changed = false;
        run();
        return changed;
    }

	private final Map<String, StaticValues> classValues = new ConcurrentHashMap<>();
	private final Map<String, InitializerInfo> initializers = new ConcurrentHashMap<>();
	private final Set<String> mutableArrays = ConcurrentHashMap.newKeySet();
	private AnalysisSupport context;

	public ReValue getStaticValue(String className, String fieldName, String fieldDesc) {

		if (fieldDesc.startsWith("[") && mutableArrays.contains(arrayKey(className, fieldName, fieldDesc)))
			return null;

		StaticValues values = classValues.get(className);
		if (values == null)
			return null;
		return values.get(fieldName, fieldDesc);
	}

	public boolean isInitializationObservable(String className) {
		Set<String> visited = new HashSet<>();
		String current = className;
		while (current != null && !current.startsWith("java/")) {
			InitializerInfo info = initializers.get(current);
			if (info == null || info.observable() || !visited.add(current))
				return true;
			current = info.superName();
		}
		return false;
	}

	@Override
	public ReValue get(FieldInsnNode field) {
		ReValue value = getStaticValue(field.owner, field.name, field.desc);
		if (value == null) {
			try {
				return Objects.requireNonNull(ReValue.ofType(Type.getType(field.desc), Nullness.UNKNOWN));
			} catch (Exception ex) {

				throw new IllegalStateException(ex);
			}
		}
		return value;
	}

	@Override
	public boolean hasLookup(FieldInsnNode field) {
		return getStaticValue(field.owner, field.name, field.desc) != null;
	}

		private void run() throws Exception {
		prepare(classes, classpath);
		for (ClassNode classNode : new ArrayList<>(classNodes()))
			collect(classNode);
	}

	public void prepare(Map<String, ClassNode> classMap, Map<String, ClassNode> classpathMap) {
		context = new AnalysisSupport(classMap, classpathMap);
	}

	public void prepare(AnalysisSupport analysisSupport) {
		context = analysisSupport;
	}

	public void collect(ClassNode node) throws TransformationException {
		StaticValues valuesContainer = new StaticValues();
		EffectivelyFinalFields finalContainer = new EffectivelyFinalFields();
		String className = node.name;

		classValues.remove(className);

		for (FieldNode field : node.fields) {
			if ((field.access & Opcodes.ACC_STATIC) == 0)
				continue;

			if ((field.access & Opcodes.ACC_FINAL) != 0)
				finalContainer.add(field.name, field.desc);
			else if ((field.access & Opcodes.ACC_PRIVATE) != 0)
				finalContainer.addMaybe(field.name, field.desc);

			Object defaultValue = field.value;
			if (defaultValue == null)
				continue;

			ReValue mappedValue = getOr(() -> ReValue.ofConstant(defaultValue), null);
			if (mappedValue == null)
				continue;

			valuesContainer.put(field.name, field.desc, mappedValue);
		}
		Map<String, MethodNode> methodsByKey = new HashMap<>();
		MethodNode clinit = null;
		for (MethodNode method : node.methods) {
			methodsByKey.put(key(method.name, method.desc), method);
			if ((method.access & Opcodes.ACC_STATIC) != 0 && method.name.equals("<clinit>") && method.desc.equals("()V"))
				clinit = method;
		}

		Set<String> initializationHelpers = new HashSet<>();
		Set<String> unresolvedHelperEdges = new HashSet<>();
		if (clinit != null)
			collectInitializationHelpers(clinit, className, methodsByKey, initializationHelpers, new HashSet<>(), unresolvedHelperEdges);

		Set<String> externallyReachableMethods = new HashSet<>();
		for (MethodNode method : node.methods) {
			if (!method.name.equals("<clinit>") && (method.access & Opcodes.ACC_PRIVATE) == 0)
				collectExternalMethods(method, className, methodsByKey, externallyReachableMethods, new HashSet<>());
		}

		for (MethodNode method : node.methods) {
			if (method.instructions == null)
				continue;
			String methodKey = key(method.name, method.desc);
			boolean isInitialization = method == clinit
					|| (initializationHelpers.contains(methodKey) && !externallyReachableMethods.contains(methodKey));
			for (AbstractInsnNode instruction : method.instructions) {

				if (instruction.getOpcode() != Opcodes.GETSTATIC
						|| !(instruction instanceof FieldInsnNode fieldInsn)
						|| !fieldInsn.desc.startsWith("["))
					continue;

				if (isInitialization && fieldInsn.owner.equals(className))
					continue;

				if (!isReadOnlyArrayUse(method, fieldInsn))
					mutableArrays.add(arrayKey(fieldInsn.owner, fieldInsn.name, fieldInsn.desc));
			}
		}

		Map<String, Set<String>> methodWrites = new HashMap<>();
		Map<String, Integer> directInitializerWrites = new HashMap<>();
		for (MethodNode method : node.methods) {
			if (method.instructions == null)
				continue;
			boolean isClinit = method == clinit;
			String methodKey = key(method.name, method.desc);
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction.getOpcode() != Opcodes.PUTSTATIC || !(instruction instanceof FieldInsnNode fieldInsn)
						|| !fieldInsn.owner.equals(className))
					continue;

				String fieldName = fieldInsn.name;
				String fieldDesc = fieldInsn.desc;
				String fieldKey = key(fieldName, fieldDesc);
				if (isClinit) {
					if (finalContainer.containsMaybe(fieldName, fieldDesc)) {
						int count = directInitializerWrites.merge(fieldKey, 1, Integer::sum);
						if (count > 1)
							finalContainer.disqualifyMaybe(fieldName, fieldDesc);
					}
				} else {

					finalContainer.removeMaybe(fieldName, fieldDesc);
					methodWrites.computeIfAbsent(methodKey, ignored -> new HashSet<>()).add(fieldKey);
				}
			}
		}

		Set<String> initializationWrittenFields = new HashSet<>();
		for (String methodKey : initializationHelpers)
			initializationWrittenFields.addAll(methodWrites.getOrDefault(methodKey, Set.of()));
		Set<String> externalWrittenFields = new HashSet<>();
		for (String methodKey : externallyReachableMethods)
			externalWrittenFields.addAll(methodWrites.getOrDefault(methodKey, Set.of()));

		GetStaticLookup staticLookup = new GetStaticLookup() {
			@Override
			public ReValue get(FieldInsnNode field) {
				if (field.owner.equals(className)) {
					ReValue localValue = valuesContainer.get(field.name, field.desc);
					if (localValue != null)
						return localValue;
				} else {
					ReValue externalValue = getStaticValue(field.owner, field.name, field.desc);
					if (externalValue != null)
						return externalValue;
				}
				try {
					return Objects.requireNonNull(ReValue.ofType(Type.getType(field.desc), Nullness.UNKNOWN));
				} catch (Exception ex) {

					throw new IllegalStateException(ex);
				}
			}

			@Override
			public boolean hasLookup(FieldInsnNode field) {
				if (field.owner.equals(className))
					return valuesContainer.get(field.name, field.desc) != null;
				return getStaticValue(field.owner, field.name, field.desc) != null;
			}
		};

		initializers.put(className, new InitializerInfo(
				isInitializerObservable(clinit, className),
				node.superName));

		finalContainer.commitMaybeIntoEffectivelyFinals();

		if (clinit != null && hasStaticSetters(clinit)) {
			try {
				ReAnalyzer analyzer = context.newAnalyzer(node, clinit);
				ReInterpreter interpreter = analyzer.getInterpreter();
				interpreter.setGetStaticLookup(staticLookup);
				Frame<ReValue>[] frames = analyzer.analyze(node.name, clinit);
				AbstractInsnNode[] instructions = clinit.instructions.toArray();
				for (int i = 0; i < instructions.length; i++) {
					AbstractInsnNode instruction = instructions[i];
					if (instruction.getOpcode() != Opcodes.PUTSTATIC || !(instruction instanceof FieldInsnNode fieldInsn)
							|| !fieldInsn.owner.equals(className))
						continue;

					String fieldName = fieldInsn.name;
					String fieldDesc = fieldInsn.desc;
					if (!finalContainer.contains(fieldName, fieldDesc))
						continue;

					Frame<ReValue> frame = frames[i];
					if (frame == null || frame.getStackSize() == 0)
						continue;
					ReValue existingValue = valuesContainer.get(fieldName, fieldDesc);
					ReValue stackValue = frame.getStack(frame.getStackSize() - 1);
					ReValue merged = existingValue == null ? stackValue : interpreter.merge(existingValue, stackValue);
					valuesContainer.put(fieldName, fieldDesc, merged);
				}
			} catch (Throwable t) {
				throw new TransformationException("Error encountered when computing static constants", t);
			}
		}

		try {
			valuesContainer.commitRemainingAsDefaults(finalContainer);
		} catch (IllegalValueException ex) {
			throw new TransformationException("Error encountered when computing default constant values", ex);
		}

		valuesContainer.retain(finalContainer);
		if (valuesContainer.staticFieldValues.isEmpty())
			classValues.remove(className);
		else
			classValues.put(className, valuesContainer);
	}

	private static void collectInitializationHelpers(MethodNode method, String className,
	                                                 Map<String, MethodNode> methodsByKey,
	                                                 Set<String> helpers, Set<String> visited,
	                                                 Set<String> unresolvedEdges) {

		String methodKey = key(method.name, method.desc);
		if (!visited.add(methodKey) || method.instructions == null)
			return;

		for (AbstractInsnNode instruction : method.instructions) {

			if (instruction.getOpcode() != Opcodes.INVOKESTATIC
					|| !(instruction instanceof MethodInsnNode methodInsn)
					|| !methodInsn.owner.equals(className))
				continue;

			String targetKey = key(methodInsn.name, methodInsn.desc);
			MethodNode target = methodsByKey.get(targetKey);

			if (target == null) {
				unresolvedEdges.add(targetKey);
				continue;
			}

			if ((target.access & Opcodes.ACC_PRIVATE) != 0
					&& (target.access & Opcodes.ACC_STATIC) != 0
					&& helpers.add(targetKey))
				collectInitializationHelpers(target, className, methodsByKey, helpers, visited, unresolvedEdges);
		}
	}

	private static void collectExternalMethods(MethodNode method, String className,
	                                           Map<String, MethodNode> methodsByKey,
	                                           Set<String> externalMethods, Set<String> visited) {

		String methodKey = key(method.name, method.desc);
		if (!visited.add(methodKey) || method.instructions == null)
			return;

		externalMethods.add(methodKey);

		for (AbstractInsnNode instruction : method.instructions) {

			if (instruction.getOpcode() != Opcodes.INVOKESTATIC
					|| !(instruction instanceof MethodInsnNode methodInsn)
					|| !methodInsn.owner.equals(className))
				continue;

			String targetKey = key(methodInsn.name, methodInsn.desc);
			MethodNode target = methodsByKey.get(targetKey);
			if (target != null
					&& (target.access & Opcodes.ACC_STATIC) != 0
					&& !target.name.equals("<clinit>"))
				collectExternalMethods(target, className, methodsByKey, externalMethods, visited);
		}
	}

	private static boolean isWrittenOutsideInitialization(String fieldKey,
	                                                      Map<String, Set<String>> methodWrites,
	                                                      Set<String> initializationHelpers) {
		for (Map.Entry<String, Set<String>> entry : methodWrites.entrySet())
			if (!initializationHelpers.contains(entry.getKey()) && entry.getValue().contains(fieldKey))
				return true;
		return false;
	}

	private static boolean isInitializerObservable(MethodNode clinit, String className) {
		if (clinit == null || clinit.instructions == null)
			return false;

		boolean evaluated = false;
		for (AbstractInsnNode instruction : clinit.instructions) {
			switch (instruction.getOpcode()) {
				case Opcodes.GETSTATIC, Opcodes.PUTSTATIC -> {
					if (instruction instanceof FieldInsnNode fieldInsn && isForeignStatic(fieldInsn, className))
						return true;
				}
				case Opcodes.MONITORENTER, Opcodes.MONITOREXIT, Opcodes.ATHROW -> {
					return true;
				}
				case Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL,
				     Opcodes.INVOKEINTERFACE, Opcodes.INVOKEDYNAMIC -> {
					if (!evaluated)
						return true;
				}
				default -> {

				}
			}
		}
		return false;
	}

	private static boolean isForeignStatic(FieldInsnNode field, String className) {
		return !field.owner.equals(className) && !field.owner.startsWith("java/");
	}

	private static boolean isReadOnlyArrayUse(MethodNode method, AbstractInsnNode arrayProducer) {
		AbstractInsnNode next = arrayProducer.getNext();
		while (next != null && (next.getOpcode() == -1 || next.getOpcode() == Opcodes.NOP))
			next = next.getNext();
		if (next == null || next.getOpcode() != Opcodes.ASTORE)
			return isDirectArrayRead(arrayProducer);

		int variable = ((VarInsnNode) next).var;
		for (AbstractInsnNode insn : method.instructions)
			if (insn.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) insn).var == variable && !isDirectArrayRead(insn))
				return false;
		return true;
	}

	private static boolean isDirectArrayRead(AbstractInsnNode arrayProducer) {

		int above = 0;
		int jumps = 0;
		for (AbstractInsnNode insn = arrayProducer.getNext(); insn != null; insn = insn.getNext()) {
			int op = insn.getOpcode();
			if (op == -1 || op == Opcodes.NOP)
				continue;

			if (op == Opcodes.GOTO) {
				if (++jumps > 8)
					return false;
				insn = ((JumpInsnNode) insn).label;
				continue;
			}
			if (op == Opcodes.ARRAYLENGTH)
				return above == 0;
			if (AsmFlowUtil.isArrayLoad(op))
				return above == 1;

			if (AsmFlowUtil.isConstIntValue(insn) || op == Opcodes.ILOAD) {
				above++;
			} else if (AsmFlowUtil.isIntBinaryOperation(op)) {

				if (above < 2)
					return false;
				above--;
			} else {
				return false;
			}
		}
		return false;
	}

	private static boolean hasStaticInvocations(MethodNode method) {
		if (method.instructions == null)
			return false;
		for (AbstractInsnNode instruction : method.instructions)
			if (instruction.getOpcode() == Opcodes.INVOKESTATIC)
				return true;
		return false;
	}

	private static String fieldName(String fieldKey) {
		return fieldKey.substring(0, fieldKey.indexOf(':'));
	}

	private static String fieldDescriptor(String fieldKey) {
		return fieldKey.substring(fieldKey.indexOf(':') + 1);
	}

	private static boolean hasStaticSetters(MethodNode method) {
		if (method.instructions == null)
			return false;
		for (AbstractInsnNode abstractInsnNode : method.instructions)
			if (abstractInsnNode.getOpcode() == Opcodes.PUTSTATIC) return true;
		return false;
	}

	private static String arrayKey(String owner, String name, String desc) {
		return owner + '.' + key(name, desc);
	}

	private static String key(String name, String desc) {
		return name + ':' + desc;
	}

	private record InitializerInfo(boolean observable, String superName) {}

	private static class EffectivelyFinalFields {
		private Set<String> finalFieldKeys;
		private Set<String> originalMaybeFinalFieldKeys;
		private Set<String> maybeFinalFieldKeys;
		private Set<String> disqualifiedMaybeFieldKeys;

		public void add(String name, String desc) {
			if (finalFieldKeys == null)
				finalFieldKeys = new HashSet<>();
			finalFieldKeys.add(key(name, desc));
		}

		public void addMaybe(String name, String desc) {
			String fieldKey = key(name, desc);
			if (originalMaybeFinalFieldKeys == null)
				originalMaybeFinalFieldKeys = new HashSet<>();
			originalMaybeFinalFieldKeys.add(fieldKey);
			if (maybeFinalFieldKeys == null)
				maybeFinalFieldKeys = new HashSet<>();
			maybeFinalFieldKeys.add(fieldKey);
		}

		public void removeMaybe(String name, String desc) {
			if (maybeFinalFieldKeys != null)
				maybeFinalFieldKeys.remove(key(name, desc));
		}

		public void disqualifyMaybe(String name, String desc) {
			String fieldKey = key(name, desc);
			if (disqualifiedMaybeFieldKeys == null)
				disqualifiedMaybeFieldKeys = new HashSet<>();
			disqualifiedMaybeFieldKeys.add(fieldKey);
			removeMaybe(name, desc);
		}

		public void promoteMaybe(String name, String desc) {
			String fieldKey = key(name, desc);
			if (originalMaybeFinalFieldKeys != null && originalMaybeFinalFieldKeys.contains(fieldKey)
					&& (disqualifiedMaybeFieldKeys == null || !disqualifiedMaybeFieldKeys.contains(fieldKey))) {
				if (maybeFinalFieldKeys == null)
					maybeFinalFieldKeys = new HashSet<>();
				maybeFinalFieldKeys.add(fieldKey);
			}
		}

		public boolean containsMaybe(String name, String desc) {
			return maybeFinalFieldKeys != null && maybeFinalFieldKeys.contains(key(name, desc));
		}

		public void commitMaybeIntoEffectivelyFinals() {
			if (maybeFinalFieldKeys != null)
				if (finalFieldKeys == null)
					finalFieldKeys = new HashSet<>(maybeFinalFieldKeys);
				else
					finalFieldKeys.addAll(maybeFinalFieldKeys);
		}

		public boolean contains(String name, String desc) {
			if (finalFieldKeys == null)
				return false;
			return finalFieldKeys.contains(key(name, desc));
		}
	}

	private static class StaticValues {
		private final Map<String, ReValue> staticFieldValues = new ConcurrentHashMap<>();

		private void put(String name, String desc, ReValue value) {
			staticFieldValues.put(key(name, desc), value);
		}

		private ReValue get(String name, String desc) {
			return staticFieldValues.get(key(name, desc));
		}

		private void commitRemainingAsDefaults(EffectivelyFinalFields finalFields) throws IllegalValueException {
			if (finalFields.finalFieldKeys == null)
				return;

			for (String key : finalFields.finalFieldKeys)
				if (!staticFieldValues.containsKey(key)) {
					Type fieldType = Type.getType(key.substring(key.indexOf(':') + 1));
					staticFieldValues.put(key, ReValue.ofTypeDefaultValue(fieldType));
				}
		}

		private void retain(EffectivelyFinalFields finalFields) {
			if (finalFields.finalFieldKeys == null) {
				staticFieldValues.clear();
				return;
			}

			staticFieldValues.keySet().removeIf(key -> !finalFields.finalFieldKeys.contains(key));
		}
	}

	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}

	private static <T> T getOr(ThrowingSupplier<T> supplier, T fallback) {
		try {
			return supplier.get();
		} catch (Throwable t) {
			return fallback;
		}
	}
}

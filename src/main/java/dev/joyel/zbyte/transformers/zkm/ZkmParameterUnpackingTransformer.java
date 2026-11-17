package dev.joyel.zbyte.transformers.zkm;

import dev.joyel.zbyte.reanalysis.AnalysisSupport;
import dev.joyel.zbyte.reanalysis.TypeHierarchy;
import dev.joyel.zbyte.reanalysis.value.*;
import dev.joyel.zbyte.reanalysis.value.impl.ObjectValueBoxImpl;
import dev.joyel.zbyte.transformers.universal.UniversalSupport;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;

public class ZkmParameterUnpackingTransformer extends Transformer<TransformerConfig> {

	private static final int MAX_PASSES = 8;
	private static final String OBJECT_ARRAY_DESCRIPTOR = "[Ljava/lang/Object;";
	private static final Type OBJECT_ARRAY_TYPE = Type.getType(OBJECT_ARRAY_DESCRIPTOR);
	private static final Type STRING_TYPE = Type.getObjectType("java/lang/String");

	private final Set<ParameterKey> seededParameters = new HashSet<>();

	@Override
	public boolean transform() throws Throwable {
		seededParameters.clear();
		Map<String, WorkingClass> working = new TreeMap<>();
		for (ClassNode current : classNodes()) {
			ClassNode copy = new ClassNode();
			current.accept(copy);
			working.put(current.name, new WorkingClass(copy));
		}
		Set<String> participants = findParticipants(working);
		if (participants.isEmpty())
			return false;

		AnalysisSupport support = new AnalysisSupport(classes, classpath);
		for (int pass = 0; pass < MAX_PASSES; pass++) {
			Map<MethodKey, MethodFacts> facts = collectFacts(support, working, participants);
			if (!applyFacts(working, participants, facts))
				break;
		}

		boolean changed = false;
		for (WorkingClass workingClass : working.values()) {
			if (!workingClass.changed)
				continue;
			classes.put(workingClass.node.name, workingClass.node);
			changed = true;
		}
		return changed;
	}

	private static Set<String> findParticipants(Map<String, WorkingClass> classes) {
		Set<String> participants = new TreeSet<>();
		for (WorkingClass workingClass : classes.values()) {
			for (MethodNode method : workingClass.node.methods) {
				if (method.instructions == null)
					continue;
				for (AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof InvokeDynamicInsnNode indy && ZkmInvokeDynamicResolver.isZkmSite(indy)) {
						participants.add(workingClass.node.name);
						break;
					}
				}
				if (participants.contains(workingClass.node.name))
					break;
			}
		}
		return participants;
	}

	
	private static Map<MethodKey, MethodFacts> collectFacts(AnalysisSupport support,
	                                                        Map<String, WorkingClass> classes,
	                                                        Set<String> participants) {
		Map<MethodKey, MethodFacts> facts = new TreeMap<>();
		for (WorkingClass workingClass : classes.values()) {
			for (MethodNode method : workingClass.node.methods) {
				if (method.instructions == null || method.instructions.size() == 0)
					continue;

				Frame<ReValue>[] frames;
				try {
					frames = support.analyze(workingClass.node, method);
				} catch (Throwable t) {
					continue;
				}

				for (int index = 0; index < method.instructions.size(); index++) {
					AbstractInsnNode instruction = method.instructions.get(index);
					if (!(instruction instanceof MethodInsnNode call))
						continue;

					Type[] argumentTypes;
					try {
						argumentTypes = Type.getArgumentTypes(call.desc);
					} catch (Throwable t) {
						continue;
					}

					Frame<ReValue> frame = index < frames.length ? frames[index] : null;
					List<ReValue> arguments = frame == null ? null : readExplicitArguments(frame, call, argumentTypes);
					mergeCallFacts(facts, call, argumentTypes, arguments, classes, participants, support.hierarchy());
				}
			}
		}
		return facts;
	}

	
	private static void mergeCallFacts(Map<MethodKey, MethodFacts> facts,
	                                   MethodInsnNode call,
	                                   Type[] argumentTypes,
	                                   List<ReValue> arguments,
	                                   Map<String, WorkingClass> classes,
	                                   Set<String> participants,
	                                   TypeHierarchy graph) {
		for (MethodKey target : resolveTargets(call, classes, participants, graph)) {
			WorkingClass targetClass = classes.get(target.owner);
			MethodNode targetMethod = targetClass == null ? null : findDeclaredMethod(targetClass.node, target.name, target.descriptor);

			if (targetMethod == null || (arguments != null && !payloadShapeMatches(targetMethod, argumentTypes, arguments)))
				continue;

			MethodFacts targetFacts = facts.computeIfAbsent(target, ignored -> new MethodFacts(target.owner));
			for (int argumentIndex = 0; argumentIndex < argumentTypes.length; argumentIndex++) {
				ReValue argument = arguments == null ? null : arguments.get(argumentIndex);
				targetFacts.merge(argumentIndex, argumentTypes[argumentIndex], argument);
			}
		}
	}

	
	private static List<ReValue> readExplicitArguments(Frame<ReValue> frame,
	                                                   MethodInsnNode call,
	                                                   Type[] argumentTypes) {
		int receiverCount = call.getOpcode() == INVOKESTATIC ? 0 : 1;
		int requiredValues = argumentTypes.length + receiverCount;
		int stackSize = frame.getStackSize();
		if (stackSize < requiredValues)
			return null;
		int firstArgument = stackSize - argumentTypes.length;
		List<ReValue> arguments = new ArrayList<>(argumentTypes.length);
		for (int index = 0; index < argumentTypes.length; index++)
			arguments.add(frame.getStack(firstArgument + index));
		return arguments;
	}

	
	private static Set<MethodKey> resolveTargets(MethodInsnNode call,
	                                             Map<String, WorkingClass> classes,
	                                             Set<String> participants,
	                                             TypeHierarchy graph) {
		Set<MethodKey> targets = new TreeSet<>();
		int opcode = call.getOpcode();

		if (opcode == INVOKESTATIC || opcode == INVOKESPECIAL) {
			if (!participants.contains(call.owner))
				return targets;

			WorkingClass owner = classes.get(call.owner);
			if (owner == null)
				return targets;

			MethodNode target = findDeclaredMethod(owner.node, call.name, call.desc);
			if (isConcrete(target))
				targets.add(new MethodKey(call.owner, call.name, call.desc));
			return targets;
		}
		if (opcode != INVOKEVIRTUAL && opcode != INVOKEINTERFACE)
			return targets;

		for (String candidateName : participants) {
			WorkingClass candidate = classes.get(candidateName);
			if (candidate == null)
				continue;

			if (!graph.isAssignableFrom(call.owner, candidateName))
				continue;

			MethodNode target = findDeclaredMethod(candidate.node, call.name, call.desc);
			if (isConcrete(target))
				targets.add(new MethodKey(candidateName, call.name, call.desc));
		}
		return targets;
	}

	
	private static boolean payloadShapeMatches(MethodNode target,
	                                           Type[] argumentTypes,
	                                           List<ReValue> arguments) {
		Map<Integer, Integer> payloadSlots = payloadParameterSlots(target);
		if (payloadSlots.isEmpty())
			return true;

		int parameterIndex = payloadSlots.values().iterator().next();
		if (parameterIndex >= argumentTypes.length || !OBJECT_ARRAY_TYPE.equals(argumentTypes[parameterIndex]))
			return true;

		ReValue payload = arguments.get(parameterIndex);
		if (!(payload instanceof ArrayValue array) || array.isNull() || array.getFirstDimensionLength().isEmpty())
			return true;

		Map<Integer, FactKind> expectedKinds = expectedPayloadKinds(target);
		if (expectedKinds.isEmpty())
			return true;

		for (Map.Entry<Integer, FactKind> entry : expectedKinds.entrySet()) {
			int index = entry.getKey();
			FactKind kind = entry.getValue();

			PrimitiveFact actual = normalizePrimitive(array.getValue(index));
			if (actual.kind == FactKind.UNKNOWN || actual.kind == FactKind.CONFLICT)
				continue;

			if (kind == FactKind.STRING && actual.kind == FactKind.NULL)
				continue;

			if (actual.kind != kind)
				return false;
		}
		return true;
	}

	
	private static Map<Integer, FactKind> expectedPayloadKinds(MethodNode target) {
		Map<Integer, FactKind> expected = new HashMap<>();
		Map<Integer, Integer> payloadSlots = payloadParameterSlots(target); // Map of local slots to Object[]
		for (AbstractInsnNode instruction : target.instructions) {
			if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD)
				continue;

			Integer parameterIndex = payloadSlots.get(load.var);
			if (parameterIndex == null)
				continue;

			AbstractInsnNode cursor = load.getNext();
			if (cursor instanceof InsnNode dup && dup.getOpcode() == DUP)
				cursor = cursor.getNext();
			Integer index = integerConstant(cursor);
			if (index == null)
				continue;
			AbstractInsnNode arrayLoad = cursor.getNext();
			if (!(arrayLoad instanceof InsnNode) || arrayLoad.getOpcode() != AALOAD)
				continue;

			AbstractInsnNode cast = arrayLoad.getNext();
			if (!(cast instanceof TypeInsnNode typeCast) || typeCast.getOpcode() != CHECKCAST)
				continue;

			FactKind kind;
			if ("java/lang/Integer".equals(typeCast.desc)
					&& cast.getNext() instanceof MethodInsnNode call
					&& call.getOpcode() == INVOKEVIRTUAL && "intValue".equals(call.name) && "()I".equals(call.desc))
				kind = FactKind.INT;
			else if ("java/lang/Long".equals(typeCast.desc)
					&& cast.getNext() instanceof MethodInsnNode call
					&& call.getOpcode() == INVOKEVIRTUAL && "longValue".equals(call.name) && "()J".equals(call.desc))
				kind = FactKind.LONG;
			else if ("java/lang/String".equals(typeCast.desc))
				kind = FactKind.STRING;
			else
				continue;
			expected.putIfAbsent(index, kind);
		}
		return expected;
	}

	
	private static boolean isConcrete(MethodNode method) {
		return method != null
				&& (method.access & (ACC_ABSTRACT | ACC_NATIVE)) == 0
				&& method.instructions != null
				&& method.instructions.size() > 0;
	}

	
	private static MethodNode findDeclaredMethod(ClassNode classNode,
	                                             String name,
	                                             String descriptor) {
		for (MethodNode method : classNode.methods)
			if (name.equals(method.name) && descriptor.equals(method.desc))
				return method;
		return null;
	}

	
	private boolean applyFacts(Map<String, WorkingClass> classes,
	                           Set<String> participants,
	                           Map<MethodKey, MethodFacts> facts) {
		boolean changed = false;
		for (String participant : participants) {
			WorkingClass workingClass = classes.get(participant);
			if (workingClass == null)
				continue;
			for (MethodNode method : workingClass.node.methods) {
				if (method.instructions == null || method.instructions.size() == 0)
					continue;

				MethodFacts methodFacts = facts.get(new MethodKey(workingClass.node.name, method.name, method.desc));
				if (methodFacts == null)
					continue;

				boolean methodChanged = seedPrimitiveParameters(method, methodFacts) | rewritePayloadReads(method, methodFacts);
				if (methodChanged) {
					workingClass.changed = true;
					changed = true;
				}
			}
		}
		return changed;
	}

	
	private boolean seedPrimitiveParameters(MethodNode method, MethodFacts facts) {
		MethodKey key = new MethodKey(facts.owner, method.name, method.desc);
		Type[] parameters;
		try {
			parameters = Type.getArgumentTypes(method.desc);
		} catch (Throwable t) {
			return false;
		}

		int slot = (method.access & ACC_STATIC) == 0 ? 1 : 0;
		InsnList stores = new InsnList();
		for (int index = 0; index < parameters.length; index++) {
			ParameterKey parameterKey = new ParameterKey(key, index);

			Type parameter = parameters[index];
			if (seededParameters.contains(parameterKey)) {
				slot += parameter.getSize();
				continue;
			}

			PrimitiveFact fact = facts.primitive(index);
			AbstractInsnNode constant = primitiveConstant(parameter, fact);
			if (constant != null && slot >= 0 && slot + parameter.getSize() <= method.maxLocals) {
				stores.add(constant);
				stores.add(varStore(slot, parameter));
				seededParameters.add(parameterKey);
			}
			slot += parameter.getSize();
		}
		if (stores.size() == 0)
			return false;

		method.instructions.insertBefore(method.instructions.getFirst(), stores);
		return true;
	}

	
	private static AbstractInsnNode primitiveConstant(Type parameterType, PrimitiveFact fact) {
		if (fact == null || fact.kind == FactKind.UNKNOWN || fact.kind == FactKind.CONFLICT)
			return null;

		return switch (parameterType.getSort()) {
			case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT ->
					fact.kind == FactKind.INT ? intToInsn((Integer) fact.value) : null;
			case Type.LONG -> fact.kind == FactKind.LONG ? longToInsn((Long) fact.value) : null;
			case Type.OBJECT -> {
				if (STRING_TYPE.equals(parameterType) && fact.kind == FactKind.STRING)
					yield new LdcInsnNode(fact.value);
				if (fact.kind == FactKind.NULL)
					yield new InsnNode(ACONST_NULL);
				yield null;
			}
			case Type.ARRAY -> fact.kind == FactKind.NULL ? new InsnNode(ACONST_NULL) : null;
			default -> null;
		};
	}

	
	private static boolean rewritePayloadReads(MethodNode method, MethodFacts facts) {
		Map<Integer, Integer> payloadSlots = payloadParameterSlots(method);
		if (payloadSlots.isEmpty())
			return false;

		if (hasPayloadMutation(method, payloadSlots.keySet()))
			return false;

		boolean changed = false;
		Map<AbstractInsnNode, ArrayFact> continuationFacts = new HashMap<>();
		AbstractInsnNode current = method.instructions.getFirst();
		while (current != null) {
			AbstractInsnNode next = current.getNext();
			ExtractionMatch match = null;
			ArrayFact array = null;

			if (current instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
				Integer parameterIndex = payloadSlots.get(load.var);
				array = parameterIndex == null ? null : facts.array(parameterIndex);
				if (array != null && !array.unknown)
					match = matchPayloadRead(load, array);
			} else if (current instanceof InsnNode duplicate && duplicate.getOpcode() == DUP) {
				array = continuationFacts.get(current);
				if (array != null && !array.unknown)
					match = matchPayloadContinuation(duplicate, array);
			}

			if (match != null) {
				AbstractInsnNode after = match.end.getNext();
				replaceRange(method.instructions, match.start, match.end, match.replacement);
				if (isWideConstant(match.replacement))
					method.maxStack++;
				if (match.continuation) {
					AbstractInsnNode following = after == null ? null : after.getNext();
					if (following instanceof InsnNode duplicate && duplicate.getOpcode() == DUP)
						continuationFacts.put(duplicate, array);
				}
				changed = true;
				current = after;
				continue;
			}
			current = next;
		}
		return changed;
	}

	
	private static boolean hasPayloadMutation(MethodNode method, Set<Integer> payloadSlots) {
		if (payloadSlots.isEmpty())
			return false;

		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(method.name, method);
		} catch (Throwable t) {
			return true;
		}

		for (int index = 0; index < method.instructions.size(); index++) {
			if (method.instructions.get(index).getOpcode() != Opcodes.AASTORE || index >= frames.length)
				continue;

			Frame<SourceValue> frame = frames[index];
			if (frame == null || frame.getStackSize() < 3)
				continue;

			SourceValue arraySource = frame.getStack(frame.getStackSize() - 3);
			for (AbstractInsnNode source : arraySource.insns)
				if (source instanceof VarInsnNode load
						&& load.getOpcode() == Opcodes.ALOAD
						&& payloadSlots.contains(load.var))
					return true;
		}
		return false;
	}

	
	private static Map<Integer, Integer> payloadParameterSlots(MethodNode method) {
		Type[] parameters;
		try {
			parameters = Type.getArgumentTypes(method.desc);
		} catch (Throwable t) {
			return Collections.emptyMap();
		}

		Map<Integer, Integer> slots = new HashMap<>();
		int slot = (method.access & ACC_STATIC) == 0 ? 1 : 0;
		for (int index = 0; index < parameters.length; index++) {
			Type parameter = parameters[index];
			if (OBJECT_ARRAY_TYPE.equals(parameter))
				slots.put(slot, index);
			slot += parameter.getSize();
		}
		return slots;
	}

	
	private static ExtractionMatch matchPayloadRead(VarInsnNode load,
	                                                ArrayFact array) {
		AbstractInsnNode cursor = load.getNext();
		boolean preserveDuplicate = cursor instanceof InsnNode dup && dup.getOpcode() == DUP;
		if (preserveDuplicate)
			cursor = cursor.getNext();

		AbstractInsnNode start = preserveDuplicate ? load.getNext() : load;
		return matchPayloadReadTail(cursor, start, array, preserveDuplicate);
	}

	
	private static ExtractionMatch matchPayloadContinuation(InsnNode duplicate,
	                                                        ArrayFact array) {
		return matchPayloadReadTail(duplicate.getNext(), duplicate, array, true);
	}

	
	private static ExtractionMatch matchPayloadReadTail(AbstractInsnNode cursor,
	                                                    AbstractInsnNode start,
	                                                    ArrayFact array,
	                                                    boolean continuation) {
		Integer index = integerConstant(cursor);
		if (index == null || index < 0)
			return null;

		PrimitiveFact fact = array.elements.get(index);
		if (fact == null || fact.kind == FactKind.UNKNOWN || fact.kind == FactKind.CONFLICT)
			return null;

		AbstractInsnNode arrayLoad = cursor.getNext();
		if (!(arrayLoad instanceof InsnNode) || arrayLoad.getOpcode() != AALOAD)
			return null;

		AbstractInsnNode terminal = arrayLoad;
		AbstractInsnNode following = terminal.getNext();
		AbstractInsnNode replacement;
		if (following instanceof TypeInsnNode cast && cast.getOpcode() == CHECKCAST) {
			if ("java/lang/Long".equals(cast.desc)) {
				AbstractInsnNode unbox = cast.getNext();
				if (!(unbox instanceof MethodInsnNode call)
						|| call.getOpcode() != INVOKEVIRTUAL
						|| !"java/lang/Long".equals(call.owner)
						|| !"longValue".equals(call.name)
						|| !"()J".equals(call.desc)
						|| fact.kind != FactKind.LONG)
					return null;
				terminal = unbox;
				replacement = longToInsn((Long) fact.value);
			} else if ("java/lang/Integer".equals(cast.desc)) {
				AbstractInsnNode unbox = cast.getNext();
				if (!(unbox instanceof MethodInsnNode call)
						|| call.getOpcode() != INVOKEVIRTUAL
						|| !"java/lang/Integer".equals(call.owner)
						|| !"intValue".equals(call.name)
						|| !"()I".equals(call.desc)
						|| fact.kind != FactKind.INT)
					return null;
				terminal = unbox;
				replacement = intToInsn((Integer) fact.value);
			} else if ("java/lang/String".equals(cast.desc)
					&& (fact.kind == FactKind.STRING || fact.kind == FactKind.NULL)) {
				terminal = cast;
				replacement = fact.kind == FactKind.STRING ? new LdcInsnNode(fact.value) : new InsnNode(ACONST_NULL);
			} else {
				return null;
			}
		} else if (fact.kind == FactKind.NULL) {
			replacement = new InsnNode(ACONST_NULL);
		} else if (fact.kind == FactKind.STRING) {
			replacement = new LdcInsnNode(fact.value);
		} else {
			return null;
		}
		return new ExtractionMatch(start, terminal, replacement, continuation);
	}

	
	private static void replaceRange(InsnList instructions,
	                                 AbstractInsnNode start,
	                                 AbstractInsnNode end,
	                                 AbstractInsnNode replacement) {
		AbstractInsnNode after = end.getNext();
		AbstractInsnNode current = start;
		while (current != after) {
			AbstractInsnNode next = current.getNext();
			instructions.remove(current);
			current = next;
		}
		if (after == null)
			instructions.add(replacement);
		else
			instructions.insertBefore(after, replacement);
	}

	
	private static Integer integerConstant(AbstractInsnNode instruction) {
		if (instruction == null)
			return null;
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

	
	private static PrimitiveFact normalizePrimitive(ReValue input) {
		if (input == null)
			return PrimitiveFact.UNKNOWN;

		if (input instanceof ObjectValue object && object.isNull())
			return PrimitiveFact.NULL;

		if (input instanceof IntValue integer)
			return integer.value().isPresent() ? PrimitiveFact.integer(integer.value().getAsInt()) : PrimitiveFact.UNKNOWN;
		if (input instanceof LongValue longValue)
			return longValue.value().isPresent() ? PrimitiveFact.longValue(longValue.value().getAsLong()) : PrimitiveFact.UNKNOWN;
		if (input instanceof StringValue string)
			return string.getText().isPresent() ? PrimitiveFact.string(string.getText().get()) : PrimitiveFact.UNKNOWN;

		if (input instanceof ObjectValueBoxImpl<?> box) {
			if (!box.hasKnownValue())
				return PrimitiveFact.UNKNOWN;
			Object value;
			try {
				value = box.unbox();
			} catch (RuntimeException ignored) {
				return PrimitiveFact.UNKNOWN;
			}
			if (value instanceof Integer integer)
				return PrimitiveFact.integer(integer);
			if (value instanceof Long longValue)
				return PrimitiveFact.longValue(longValue);
		}

		return PrimitiveFact.UNKNOWN;
	}

	
	private static PrimitiveFact merge(PrimitiveFact current, PrimitiveFact incoming) {
		if (current == null)
			return incoming;
		if (current.kind == FactKind.CONFLICT || incoming.kind == FactKind.CONFLICT)
			return PrimitiveFact.CONFLICT;
		if (current.kind == FactKind.UNKNOWN || incoming.kind == FactKind.UNKNOWN)
			return current.kind == incoming.kind ? PrimitiveFact.UNKNOWN : PrimitiveFact.CONFLICT;
		return current.kind == incoming.kind && Objects.equals(current.value, incoming.value) ? current : PrimitiveFact.CONFLICT;
	}

	

	
	private static final class WorkingClass {
		private final ClassNode node;
		private boolean changed;

		private WorkingClass(ClassNode node) {
			this.node = node;
		}
	}

	
	private static final class MethodFacts {
		private final String owner;
		private final Map<Integer, ParameterFact> parameters = new HashMap<>();

		private MethodFacts(String owner) {
			this.owner = owner;
		}

		private void merge(int index, Type formalType, ReValue value) {
			ParameterFact parameter = parameters.computeIfAbsent(index, ignored -> new ParameterFact());
			if (OBJECT_ARRAY_TYPE.equals(formalType))
				parameter.mergeArray(value);
			else
				parameter.merge(normalizePrimitive(value));
		}

		private PrimitiveFact primitive(int index) {
			ParameterFact fact = parameters.get(index);
			return fact == null ? null : fact.primitive;
		}

		private ArrayFact array(int index) {
			ParameterFact fact = parameters.get(index);
			return fact == null ? null : fact.array;
		}
	}

	
	private static final class ParameterFact {
		private PrimitiveFact primitive;
		private ArrayFact array;

		private void merge(PrimitiveFact value) {
			if (value != null)
				primitive = ZkmParameterUnpackingTransformer.merge(primitive, value);
		}

		private void mergeArray(ReValue value) {
			if (array == null)
				array = new ArrayFact();
			array.merge(value);
		}
	}

	
	private static final class ArrayFact {
		private final Map<Integer, PrimitiveFact> elements = new HashMap<>();
		private boolean unknown;
		private Integer length;

		private void merge(ReValue value) {
			if (unknown)
				return;

			if (!(value instanceof ArrayValue arrayValue) || arrayValue.isNull()) {
				unknown = true;
				return;
			}
			OptionalInt lengthValue = arrayValue.getFirstDimensionLength();
			if (lengthValue.isEmpty() || lengthValue.getAsInt() < 0) {
				unknown = true;
				return;
			}

			int currentLength = lengthValue.getAsInt();
			if (length != null && length != currentLength) {
				unknown = true;
				return;
			}

			length = currentLength;
			for (int index = 0; index < currentLength; index++)
				elements.put(index, ZkmParameterUnpackingTransformer.merge(elements.get(index), normalizePrimitive(arrayValue.getValue(index))));
		}
	}

	
	private enum FactKind {
		
		INT,
		
		LONG,
		
		STRING,
		
		NULL,
		
		UNKNOWN,
		
		CONFLICT
	}

	
	private record PrimitiveFact(FactKind kind, Object value) {
		private static final PrimitiveFact UNKNOWN = new PrimitiveFact(FactKind.UNKNOWN, null);
		private static final PrimitiveFact CONFLICT = new PrimitiveFact(FactKind.CONFLICT, null);
		private static final PrimitiveFact NULL = new PrimitiveFact(FactKind.NULL, null);

		private static PrimitiveFact integer(int value) {
			return new PrimitiveFact(FactKind.INT, value);
		}

		private static PrimitiveFact longValue(long value) {
			return new PrimitiveFact(FactKind.LONG, value);
		}

		private static PrimitiveFact string(String value) {
			return new PrimitiveFact(FactKind.STRING, value);
		}
	}

	
	private record MethodKey(String owner, String name,
	                         String descriptor) implements Comparable<MethodKey> {
		@Override
		public int compareTo(MethodKey o) {
			int cmp = owner.compareTo(o.owner);
			if (cmp != 0) return cmp;
			cmp = name.compareTo(o.name);
			if (cmp != 0) return cmp;
			return descriptor.compareTo(o.descriptor);
		}
	}

	
	private record ParameterKey(MethodKey method, int index) {}

	
	private record ExtractionMatch(AbstractInsnNode start,
	                               AbstractInsnNode end,
	                               AbstractInsnNode replacement,
	                               boolean continuation) {}

	private static boolean isWideConstant(AbstractInsnNode instruction) {
		int opcode = instruction.getOpcode();
		if (opcode == LCONST_0 || opcode == LCONST_1 || opcode == DCONST_0 || opcode == DCONST_1)
			return true;
		return instruction instanceof LdcInsnNode ldc && (ldc.cst instanceof Long || ldc.cst instanceof Double);
	}

	private static AbstractInsnNode longToInsn(long value) {
		return UniversalSupport.constantInsn(value);
	}

	private static AbstractInsnNode intToInsn(int value) {
		return UniversalSupport.constantInsn(value);
	}

	private static VarInsnNode varStore(int index, Type type) {
		switch (type.getSort()) {
			case Type.BOOLEAN:
			case Type.CHAR:
			case Type.BYTE:
			case Type.SHORT:
			case Type.INT:
				return new VarInsnNode(ISTORE, index);
			case Type.FLOAT:
				return new VarInsnNode(FSTORE, index);
			case Type.DOUBLE:
				return new VarInsnNode(DSTORE, index);
			case Type.LONG:
				return new VarInsnNode(LSTORE, index);
			default:
				return new VarInsnNode(ASTORE, index);
		}
	}
}

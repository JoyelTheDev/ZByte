package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;

import dev.joyel.zbyte.analysis.AnalysisSupport;
import dev.joyel.zbyte.analysis.TransformationException;
import dev.joyel.zbyte.analysis.TypeHierarchy;
import dev.joyel.zbyte.analysis.Types;
import dev.joyel.zbyte.utils.collections.Int2ObjectMap;
import dev.joyel.zbyte.analysis.value.ArrayValue;
import dev.joyel.zbyte.analysis.value.IntValue;
import dev.joyel.zbyte.analysis.value.LongValue;
import dev.joyel.zbyte.analysis.value.ObjectValue;
import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.utils.AsmFlowUtil;
import java.util.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;

import static dev.joyel.zbyte.utils.AsmFlowUtil.*;
import static org.objectweb.asm.Opcodes.*;

@TransformerConfig.ConfigOptions(configClass = RedundantTryCatchRemovingTransformer.Config.class)
public class RedundantTryCatchRemovingTransformer extends Transformer<RedundantTryCatchRemovingTransformer.Config> {
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
	private static final String EX_NPE = "java/lang/NullPointerException";
	private static final String EX_ASE = "java/lang/ArrayStoreException";
	private static final String EX_AIOOBE = "java/lang/ArrayIndexOutOfBoundsException";
	private static final String EX_NASE = "java/lang/NegativeArraySizeException";
	private static final String EX_IMSE = "java/lang/IllegalMonitorStateException";
	private static final String EX_CCE = "java/lang/ClassCastException";
	private static final String EX_AE = "java/lang/ArithmeticException";

	private AnalysisSupport context;
	private TypeHierarchy hierarchy;
	private Set<String> thrownExceptions;
	private boolean deleteJunkWorkspaceExceptions;


		private void run() throws Exception {
		context = new AnalysisSupport(classes, classpath);
		deleteJunkWorkspaceExceptions = getConfig().isDeleteJunkWorkspaceExceptions();
		hierarchy = context.hierarchy();
		thrownExceptions = ExceptionCollectionTransformer.collect(classes, classpath);
		for (ClassNode classNode : new ArrayList<>(classNodes()))
			transformClass(classNode);
	}

	private void transformClass(ClassNode classNode) throws TransformationException {
		ClassNode node = classNode;
		boolean dirty = false;
		for (MethodNode method : node.methods) {

			if (method.instructions == null || method.instructions.size() == 0)
				continue;
			if (method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty())
				continue;

			try {
				dirty |= pruneRedundantTryCatches(context, node, method);
			} catch (TransformationException ex) {
				throw ex;
			} catch (Throwable t) {
				throw new TransformationException("Error encountered when removing redundant try-catch blocks", t);
			}
		}

		if (deleteJunkWorkspaceExceptions) {

			String className = node.name;
			if (hierarchy.isAssignableFrom("java/lang/Throwable", className)
					&& isWorkspaceExceptionNeverThrown(className))
			{
				classes.remove(classNode.name);
				markChange();
			}
		}

		if (dirty)
			markChange();
	}

	private boolean pruneRedundantTryCatches(AnalysisSupport context,
	                                         ClassNode declaringClass,
	                                         MethodNode method) throws TransformationException {
		InsnList instructions = method.instructions;

		Map<AbstractInsnNode, Integer> instructionIndices = AsmFlowUtil.indexInstructions(instructions);
		List<TryCatchState> originalState = snapshotStates(instructions, instructionIndices, method.tryCatchBlocks);

		List<TryCatchBlockNode> tryCatches = new ArrayList<>(method.tryCatchBlocks);
		Map<LabelNode, Integer> handlerUseCounts = new IdentityHashMap<>();
		for (TryCatchBlockNode block : method.tryCatchBlocks)
			handlerUseCounts.merge(block.handler, 1, Integer::sum);

		boolean hasUniqueHandler = false;
		for (TryCatchBlockNode block : method.tryCatchBlocks) {
			if (handlerUseCounts.getOrDefault(block.handler, 0) == 1) {
				hasUniqueHandler = true;
				break;
			}
		}

		NormalFlow flow = null;
		if (hasUniqueHandler && instructions.size() >= 3) {
			for (TryCatchBlockNode block : method.tryCatchBlocks) {
				if (handlerUseCounts.getOrDefault(block.handler, 0) == 1 && instructionIndices.containsKey(block.handler)) {
					flow = NormalFlow.buildNormalFlow(instructions);
					break;
				}
			}
		}
		NormalFlow rethrowFlow = flow;
		tryCatches.removeIf(block -> isTransparentRethrowHandler(instructions, block, handlerUseCounts, instructionIndices, rethrowFlow));
		tryCatches = mergeContinuousRanges(instructions, instructionIndices, tryCatches);
		tryCatches = removeExactDuplicates(instructions, instructionIndices, tryCatches);
		Map<String, Set<String>> assignableTypeNames = new HashMap<>();
		tryCatches = removeShadowedRanges(instructions, instructionIndices, tryCatches, assignableTypeNames);

		Frame<ReValue>[] frames;
		try {
			frames = context.analyze(declaringClass, method);
		} catch (Throwable t) {

			return false;
		}
		tryCatches = removeImpossibleCatches(instructions, instructionIndices, frames, tryCatches);

		List<TryCatchState> updatedState = snapshotStates(instructions, instructionIndices, tryCatches);
		if (originalState.equals(updatedState))
			return false;

		method.tryCatchBlocks.clear();
		method.tryCatchBlocks.addAll(tryCatches);
		context.pruneDeadCode(declaringClass, method);
		return true;
	}

	private static boolean isTransparentRethrowHandler(InsnList instructions,
	                                                   TryCatchBlockNode candidate,
	                                                   Map<LabelNode, Integer> handlerUseCounts,
	                                                   Map<AbstractInsnNode, Integer> instructionIndices,
	                                                   NormalFlow flow) {

		if (handlerUseCounts.getOrDefault(candidate.handler, 0) != 1)
			return false;

		if (instructions.size() < 3)
			return false;

		Integer handlerPosition = instructionIndices.get(candidate.handler);
		if (handlerPosition == null || flow == null)
			return false;
		int handlerIndex = handlerPosition;
		boolean[] reachable = flow.reachable;
		if (reachable[handlerIndex])
			return false;

		for (int predecessor : flow.predecessors.getOrDefault(handlerIndex, Collections.emptyList()))
			if (predecessor >= 0 && predecessor < reachable.length && reachable[predecessor])
				return false;

		List<Integer> bodyIndices = new ArrayList<>();
		List<AbstractInsnNode> realInstructions = new ArrayList<>(3);
		for (AbstractInsnNode current = candidate.handler; current != null; current = current.getNext()) {
			Integer index = instructionIndices.get(current);
			if (index == null)
				return false;
			bodyIndices.add(index);

			if (current.getOpcode() < 0)
				continue;
			realInstructions.add(current);

			if (current.getOpcode() == ATHROW || realInstructions.size() >= 3)
				break;
		}

		boolean direct = realInstructions.size() == 1 && realInstructions.get(0).getOpcode() == ATHROW;
		boolean identity = realInstructions.size() == 3
				&& realInstructions.get(0).getOpcode() == ASTORE
				&& realInstructions.get(1).getOpcode() == ALOAD
				&& realInstructions.get(2).getOpcode() == ATHROW
				&& realInstructions.get(0) instanceof VarInsnNode first
				&& realInstructions.get(1) instanceof VarInsnNode second
				&& first.var == second.var;
		if (!direct && !identity)
			return false;

		Set<Integer> body = new HashSet<>(bodyIndices);
		for (int bodyIndex : bodyIndices) {
			if (reachable[bodyIndex])
				return false;
			for (int predecessor : flow.predecessors.getOrDefault(bodyIndex, Collections.emptyList()))
				if (predecessor >= 0
						&& predecessor < reachable.length
						&& reachable[predecessor]
						&& !body.contains(predecessor))
					return false;
		}

		return true;
	}

	private static List<TryCatchBlockNode> mergeContinuousRanges(InsnList instructions,
	                                                             Map<AbstractInsnNode, Integer> instructionIndices,
	                                                             List<TryCatchBlockNode> tryCatches) {

		if (tryCatches.size() <= 1)
			return new ArrayList<>(tryCatches);

		List<TryCatchBlockNode> merged = new ArrayList<>(tryCatches.size());
		TryCatchBlockNode previous = null;
		for (TryCatchBlockNode current : tryCatches) {
			if (previous != null &&
					Objects.equals(previous.type, current.type) &&
					codeBoundaryIndex(instructions, instructionIndices, previous.end) == codeBoundaryIndex(instructions, instructionIndices, current.start) &&
					codeBoundaryIndex(instructions, instructionIndices, previous.handler) == codeBoundaryIndex(instructions, instructionIndices, current.handler)) {
				previous.end = current.end;
				continue;
			}

			merged.add(current);
			previous = current;
		}
		return merged;
	}

	private static List<TryCatchBlockNode> removeExactDuplicates(InsnList instructions,
	                                                             Map<AbstractInsnNode, Integer> instructionIndices,
	                                                             List<TryCatchBlockNode> tryCatches) {

		if (tryCatches.size() <= 1)
			return new ArrayList<>(tryCatches);

		Set<TryCatchState> seen = new HashSet<>(tryCatches.size());
		List<TryCatchBlockNode> kept = new ArrayList<>(tryCatches.size());
		for (TryCatchBlockNode tryCatch : tryCatches)
			if (seen.add(snapshotState(instructions, instructionIndices, tryCatch)))
				kept.add(tryCatch);
		return kept;
	}

	private List<TryCatchBlockNode> removeShadowedRanges(InsnList instructions,
	                                                     Map<AbstractInsnNode, Integer> instructionIndices,
	                                                     List<TryCatchBlockNode> tryCatches,
	                                                     Map<String, Set<String>> assignableTypeNames) {
		List<TryCatchState> states = snapshotStates(instructions, instructionIndices, tryCatches);
		List<TryCatchBlockNode> kept = new ArrayList<>(tryCatches.size());
		Map<String, RangeFrontier> rangesByType = new HashMap<>();

		for (int i = 0; i < tryCatches.size(); i++) {
			TryCatchBlockNode tryCatch = tryCatches.get(i);
			TryCatchState state = states.get(i);
			if (state.start >= state.end) {

				kept.add(tryCatch);
				continue;
			}

			RangeFrontier catchAllRanges = rangesByType.get(null);
			boolean shadowed = catchAllRanges != null && catchAllRanges.covers(state.start, state.end);

			String catchType = tryCatch.type;
			if (!shadowed && catchType != null) {
				Set<String> broaderTypes = assignableTypeNames.computeIfAbsent(catchType, hierarchy::getAssignableSupertypes);
				for (String broaderType : broaderTypes) {
					RangeFrontier ranges = rangesByType.get(broaderType);
					if (ranges != null && ranges.covers(state.start, state.end)) {
						shadowed = true;
						break;
					}
				}
			}

			if (!shadowed) {
				kept.add(tryCatch);
				rangesByType.computeIfAbsent(catchType, ignored -> new RangeFrontier()).add(state.start, state.end);
			}
		}
		return kept;
	}

	private List<TryCatchBlockNode> removeImpossibleCatches(InsnList instructions,
	                                                        Map<AbstractInsnNode, Integer> instructionIndices,
	                                                        Frame<ReValue>[] frames,
	                                                        List<TryCatchBlockNode> tryCatches) {
		List<TryCatchState> states = snapshotStates(instructions, instructionIndices, tryCatches);
		List<TryCatchBlockNode> kept = new ArrayList<>(tryCatches.size());
		for (int i = 0; i < tryCatches.size(); i++)
			if (canCatchBeUsed(instructions, frames, tryCatches.get(i), states.get(i)))
				kept.add(tryCatches.get(i));
		return kept;
	}

	private boolean canCatchBeUsed(InsnList instructions,
	                               Frame<ReValue>[] frames,
	                               TryCatchBlockNode tryCatch,
	                               TryCatchState state) {

		String catchType = tryCatch.type;
		if (catchType != null && isWorkspaceExceptionNeverThrown(catchType))
			return false;

		int start = state.start;
		int end = state.end;
		if (start >= end)
			return false;

		boolean[] visited = computeVisitedInstructions(instructions, frames, start, end);

		for (int i = start; i < end; i++) {

			if (!visited[i])
				continue;

			Frame<ReValue> frame = i < frames.length ? frames[i] : null;
			if (frame == null)
				continue;

			AbstractInsnNode insn = instructions.get(i);
			if (catchType == null) {
				if (canInsnThrowAnyException(insn, frame))
					return true;
			} else if (canInsnThrowCaughtException(insn, frame, catchType)) {
				return true;
			}
		}

		return false;
	}

	private static boolean[] computeVisitedInstructions(InsnList instructions,
	                                                    Frame<ReValue>[] frames,
	                                                    int start, int end) {

		Int2ObjectMap<List<Integer>> successorMap = new Int2ObjectMap<>();
		Int2ObjectMap<List<Integer>> predecessorMap = new Int2ObjectMap<>();

		MethodNode temp = new MethodNode();
		temp.instructions = instructions;
		temp.tryCatchBlocks = Collections.emptyList();

		AsmFlowUtil.populateFlowMaps(temp, successorMap, predecessorMap, false);

		int size = instructions.size();
		Deque<Integer> queue = new ArrayDeque<>();
		boolean[] visited = new boolean[size];
		for (int i = start; i < end && i < size; i++) {
			boolean hasOutsidePredecessor = false;
			for (int predecessor : predecessorMap.getOrDefault(i, Collections.emptyList())) {
				if (predecessor < start || predecessor >= end) {
					hasOutsidePredecessor = true;
					break;
				}
			}
			if (hasOutsidePredecessor || i == start) {
				queue.add(i);
				visited[i] = true;
			}
		}

		if (queue.isEmpty() && start < frames.length && frames[start] != null) {
			queue.add(start);
			visited[start] = true;
		}

		while (!queue.isEmpty()) {
			int cur = queue.removeFirst();
			for (int to : successorMap.getOrDefault(cur, Collections.emptyList())) {
				if (to >= start && to < end && !visited[to]) {
					visited[to] = true;
					queue.addLast(to);
				}
			}
		}

		return visited;
	}

	private boolean isWorkspaceExceptionNeverThrown(String tryCatchType) {
		ClassNode declared = classes.get(tryCatchType);
		if (declared == null || (declared.access & ACC_MODULE) != 0)
			return false;
		return !thrownExceptions.contains(tryCatchType);
	}

	private boolean canInsnThrowAnyException(AbstractInsnNode insn, Frame<ReValue> frame) {

		if (insn instanceof MethodInsnNode)
			return true;

		return switch (insn.getOpcode()) {
			case ATHROW, FDIV, FREM, DDIV, DREM -> true;
			case ARRAYLENGTH, MONITORENTER, GETFIELD -> isReferencePossiblyNull(peekStack(frame, 0));
			case PUTFIELD -> isReferencePossiblyNull(peekStack(frame, 1));
			case IALOAD, LALOAD, FALOAD, DALOAD, AALOAD, BALOAD, CALOAD, SALOAD ->
					canArrayAccessThrow(peekStack(frame, 1), peekStack(frame, 0));
			case IASTORE, LASTORE, FASTORE, DASTORE, AASTORE, BASTORE, CASTORE, SASTORE ->
					canArrayStoreThrow(insn.getOpcode(), peekStack(frame, 2), peekStack(frame, 1), peekStack(frame, 0));
			case IDIV, IREM, LDIV, LREM -> isZeroDivisorPossible(peekStack(frame, 0));
			case NEWARRAY, ANEWARRAY -> isNegativeSizePossible(peekStack(frame, 0));
			case MULTIANEWARRAY -> isNegativeMultiArraySizePossible((MultiANewArrayInsnNode) insn, frame);
			case CHECKCAST -> canCheckCastThrow((TypeInsnNode) insn, peekStack(frame, 0));
			case MONITOREXIT ->
					isReferencePossiblyNull(peekStack(frame, 0)) || !isReferenceKnownNull(peekStack(frame, 0));
			default -> false;
		};
	}

	private boolean canInsnThrowCaughtException(AbstractInsnNode insn, Frame<ReValue> frame, String catchType) {

		if (insn instanceof MethodInsnNode min)
			return true;

		return switch (insn.getOpcode()) {
			case ATHROW -> canAthrowThrow(catchType, peekStack(frame, 0));
			case GETFIELD, ARRAYLENGTH, MONITORENTER -> canThrowNullPointer(catchType, peekStack(frame, 0));
			case PUTFIELD -> canThrowNullPointer(catchType, peekStack(frame, 1));
			case IALOAD, LALOAD, FALOAD, DALOAD, AALOAD, BALOAD, CALOAD, SALOAD ->
					canArrayAccessThrow(catchType, peekStack(frame, 1), peekStack(frame, 0));
			case IASTORE, LASTORE, FASTORE, DASTORE, AASTORE, BASTORE, CASTORE, SASTORE ->
					canArrayStoreThrow(catchType, insn.getOpcode(), peekStack(frame, 2), peekStack(frame, 1), peekStack(frame, 0));
			case IDIV, IREM, LDIV, LREM, FDIV, FREM, DDIV, DREM ->
					canArithmeticThrow(catchType, insn.getOpcode(), peekStack(frame, 0));
			case NEWARRAY, ANEWARRAY ->
					isCaughtException(catchType, EX_NASE) && isNegativeSizePossible(peekStack(frame, 0));
			case MULTIANEWARRAY ->
					isCaughtException(catchType, EX_NASE) && isNegativeMultiArraySizePossible((MultiANewArrayInsnNode) insn, frame);
			case CHECKCAST ->
					isCaughtException(catchType, EX_CCE) && canCheckCastThrow((TypeInsnNode) insn, peekStack(frame, 0));
			case MONITOREXIT -> canMonitorExitThrow(catchType, peekStack(frame, 0));
			default -> false;
		};
	}

	private static boolean canArrayAccessThrow(ReValue arrayValue, ReValue indexValue) {
		return isReferencePossiblyNull(arrayValue) || canArrayIndexThrow(arrayValue, indexValue);
	}

	private boolean canArrayAccessThrow(String catchType, ReValue arrayValue, ReValue indexValue) {
		return canThrowNullPointer(catchType, arrayValue) ||
				(isCaughtException(catchType, EX_AIOOBE) &&
						canArrayIndexThrow(arrayValue, indexValue));
	}

	private boolean canArrayStoreThrow(int opcode, ReValue arrayValue, ReValue indexValue, ReValue storedValue) {
		if (isReferencePossiblyNull(arrayValue) || canArrayIndexThrow(arrayValue, indexValue))
			return true;
		return opcode == AASTORE && canArrayStoreTypeThrow(arrayValue, storedValue);
	}

	private boolean canArrayStoreThrow(String catchType, int opcode, ReValue arrayValue, ReValue indexValue, ReValue storedValue) {
		if (canThrowNullPointer(catchType, arrayValue))
			return true;
		if (isCaughtException(catchType, EX_AIOOBE) && canArrayIndexThrow(arrayValue, indexValue))
			return true;
		return opcode == AASTORE
				&& isCaughtException(catchType, EX_ASE)
				&& canArrayStoreTypeThrow(arrayValue, storedValue);
	}

	private boolean canArithmeticThrow(String catchType, int opcode, ReValue divisor) {

		if (!isCaughtException(catchType, EX_AE))
			return false;

		if (opcode == FDIV || opcode == FREM || opcode == DDIV || opcode == DREM)
			return false;

		return isZeroDivisorPossible(divisor);
	}

	private static boolean isZeroDivisorPossible(ReValue value) {
		if (value instanceof IntValue intValue)
			return intValue.value().isEmpty() || intValue.value().getAsInt() == 0;
		if (value instanceof LongValue longValue)
			return longValue.value().isEmpty() || longValue.value().getAsLong() == 0L;
		return true;
	}

	private boolean canAthrowThrow(String catchType, ReValue objectValue) {
		if (objectValue instanceof ObjectValue object) {

			if (object.isNull())
				return isCaughtException(catchType, EX_NPE);

			if (!object.isNotNull() && isCaughtException(catchType, EX_NPE))
				return true;

			Type valueType = object.type();
			if (valueType.getSort() != Type.OBJECT)
				return true;
			return canReferenceRuntimeTypeMatch(valueType, catchType);
		}

		return true;
	}

	private boolean canMonitorExitThrow(String catchType, ReValue monitorValue) {
		return canThrowNullPointer(catchType, monitorValue)
				|| (isCaughtException(catchType, EX_IMSE)
				&& !isReferenceKnownNull(monitorValue));
	}

	private boolean canCheckCastThrow(TypeInsnNode cast, ReValue value) {

		if (isReferenceKnownNull(value))
			return false;

		Type sourceType = value == null ? null : value.type();
		if (sourceType == null)
			return true;

		int sourceSort = sourceType.getSort();
		if (sourceSort != Type.OBJECT && sourceSort != Type.ARRAY)
			return false;

		Type targetType = Type.getObjectType(cast.desc);
		return !context.isAssignable(targetType, sourceType);
	}

	private boolean canArrayStoreTypeThrow(ReValue arrayValue, ReValue storedValue) {
		if (isReferenceKnownNull(arrayValue))
			return false;
		if (storedValue instanceof ObjectValue object && object.isNull())
			return false;
		if (!(arrayValue instanceof ArrayValue array))
			return true;

		Type arrayType = array.type();
		if (arrayType.getSort() != Type.ARRAY)
			return true;

		Type componentType = Types.undimension(arrayType);
		Type valueType = storedValue == null ? null : storedValue.type();
		if (valueType == null)
			return true;

		return !context.isAssignable(componentType, valueType);
	}

	private static boolean isNegativeSizePossible(ReValue value) {
		if (value instanceof IntValue intValue && intValue.value().isPresent())
			return intValue.value().getAsInt() < 0;
		return true;
	}

	private static boolean isNegativeMultiArraySizePossible(MultiANewArrayInsnNode multiArray, Frame<ReValue> frame) {
		for (int i = 0; i < multiArray.dims; i++)
			if (isNegativeSizePossible(peekStack(frame, i)))
				return true;
		return false;
	}

	private static boolean canArrayIndexThrow(ReValue arrayValue, ReValue indexValue) {

		if (isReferenceKnownNull(arrayValue))
			return false;

		if (!(indexValue instanceof IntValue index) || index.value().isEmpty())
			return true;
		if (!(arrayValue instanceof ArrayValue array))
			return true;
		OptionalInt length = array.getFirstDimensionLength();
		if (length.isEmpty())
			return true;

		int literalIndex = index.value().getAsInt();
		return literalIndex < 0 || literalIndex >= length.getAsInt();
	}

	private boolean canThrowNullPointer(String catchType, ReValue value) {
		return isCaughtException(catchType, EX_NPE) && isReferencePossiblyNull(value);
	}

	private boolean canReferenceRuntimeTypeMatch(Type type, String catchType) {
		if (type.getSort() != Type.OBJECT)
			return false;

		String typeName = type.getInternalName();
		return isCaughtException(catchType, typeName)
				|| hierarchy.isAssignableFrom(typeName, catchType);
	}

	private boolean isCaughtException(String catchType, String thrownType) {
		return catchType.equals(thrownType)
				|| hierarchy.isAssignableFrom(catchType, thrownType);
	}

	private static boolean isReferencePossiblyNull(ReValue value) {
		return !(value instanceof ObjectValue object) || !object.isNotNull();
	}

	private static boolean isReferenceKnownNull(ReValue value) {
		return value instanceof ObjectValue object && object.isNull();
	}

	private static ReValue peekStack(Frame<ReValue> frame, int offsetFromTop) {
		int index = frame.getStackSize() - 1 - offsetFromTop;
		if (index < 0)
			return null;
		return frame.getStack(index);
	}

	private static int codeBoundaryIndex(InsnList instructions,
	                                     Map<AbstractInsnNode, Integer> instructionIndices,
	                                     LabelNode label) {
		AbstractInsnNode current = AsmFlowUtil.getNextInsn(label);
		if (current == null)
			return instructions.size();
		Integer index = instructionIndices.get(current);
		return index != null ? index : -1;
	}

	private static TryCatchState snapshotState(InsnList instructions,
	                                           Map<AbstractInsnNode, Integer> instructionIndices,
	                                           TryCatchBlockNode tryCatch) {
		return new TryCatchState(codeBoundaryIndex(instructions, instructionIndices, tryCatch.start),
				codeBoundaryIndex(instructions, instructionIndices, tryCatch.end),
				codeBoundaryIndex(instructions, instructionIndices, tryCatch.handler),
				tryCatch.type);
	}

	private static List<TryCatchState> snapshotStates(InsnList instructions,
	                                                  Map<AbstractInsnNode, Integer> instructionIndices,
	                                                  List<TryCatchBlockNode> tryCatches) {
		List<TryCatchState> states = new ArrayList<>(tryCatches.size());
		for (TryCatchBlockNode tryCatch : tryCatches)
			states.add(snapshotState(instructions, instructionIndices, tryCatch));
		return states;
	}

	private record TryCatchState(int start, int end, int handler, String type) {}

	private record NormalFlow(Int2ObjectMap<List<Integer>> successors,
	                          Int2ObjectMap<List<Integer>> predecessors,
	                          boolean[] reachable) {

		private static NormalFlow buildNormalFlow(InsnList instructions) {

			Int2ObjectMap<List<Integer>> successors = new Int2ObjectMap<>(instructions.size());
			Int2ObjectMap<List<Integer>> predecessors = new Int2ObjectMap<>(instructions.size());
			MethodNode flowMethod = new MethodNode();
			flowMethod.instructions = instructions;
			flowMethod.tryCatchBlocks = Collections.emptyList();
			AsmFlowUtil.populateFlowMaps(flowMethod, successors, predecessors, false);

			boolean[] reachable = new boolean[instructions.size()];
			reachable[0] = true;
			Deque<Integer> queue = new ArrayDeque<>();
			queue.add(0);
			while (!queue.isEmpty()) {
				int current = queue.removeFirst();
				for (int next : successors.getOrDefault(current, Collections.emptyList()))
					if (next >= 0 && next < reachable.length && !reachable[next]) {
						reachable[next] = true;
						queue.addLast(next);
					}
			}
			return new NormalFlow(successors, predecessors, reachable);
		}
	}

	private static final class RangeFrontier {
		private final NavigableMap<Integer, Integer> endsByStart = new TreeMap<>();

		private boolean covers(int start, int end) {
			Map.Entry<Integer, Integer> candidate = endsByStart.floorEntry(start);
			return candidate != null && candidate.getValue() >= end;
		}

		private void add(int start, int end) {
			Map.Entry<Integer, Integer> previous = endsByStart.floorEntry(start);
			if (previous != null && previous.getValue() >= end)
				return;

			Map.Entry<Integer, Integer> next = endsByStart.ceilingEntry(start);
			while (next != null && next.getValue() <= end) {
				endsByStart.remove(next.getKey());
				next = endsByStart.ceilingEntry(start);
			}
			endsByStart.put(start, end);
		}
	}

    public static class Config extends TransformerConfig {

        private boolean deleteJunkWorkspaceExceptions;

        public Config() {
            super(RedundantTryCatchRemovingTransformer.class);
        }

        public boolean isDeleteJunkWorkspaceExceptions() {
            return deleteJunkWorkspaceExceptions;
        }

        public void setDeleteJunkWorkspaceExceptions(boolean deleteJunkWorkspaceExceptions) {
            this.deleteJunkWorkspaceExceptions = deleteJunkWorkspaceExceptions;
        }
    }
}

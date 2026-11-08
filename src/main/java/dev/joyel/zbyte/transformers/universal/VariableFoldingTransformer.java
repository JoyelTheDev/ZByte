package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import java.util.*;

import dev.joyel.zbyte.analysis.AnalysisSupport;
import dev.joyel.zbyte.analysis.TransformationException;
import dev.joyel.zbyte.analysis.Types;
import dev.joyel.zbyte.utils.collections.Int2ObjectMap;
import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.utils.AsmFlowUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import static dev.joyel.zbyte.utils.AsmFlowUtil.*;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyNavigableSet;

public class VariableFoldingTransformer extends Transformer<TransformerConfig> {
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
	private AnalysisSupport context;

		private void run() throws Exception {
		context = new AnalysisSupport(classes, classpath);
		for (ClassNode classNode : new ArrayList<>(classNodes()))
			transformClass(classNode);
	}

	private void transformClass(ClassNode node) throws TransformationException {
		boolean dirty = false;
		String className = node.name;
		for (MethodNode method : node.methods) {

			InsnList instructions = method.instructions;
			if (instructions == null)
				continue;

			dirty |= foldSingleConstantLocals(className, method);

			dirty |= removeUnusedLocals(method);

			Int2ObjectMap<List<Integer>> successorMap = new Int2ObjectMap<>();
			Int2ObjectMap<List<Integer>> predecessorMap = new Int2ObjectMap<>();
			populateFlowMaps(method, successorMap, predecessorMap);

			int size = instructions.size();
			List<Set<Integer>> inLive = new ArrayList<>(size);
			List<Set<Integer>> outLive = new ArrayList<>(size);
			populateLiveness(method, inLive, outLive, successorMap, predecessorMap);

			Int2ObjectMap<LocalAccessState> accessStates = new Int2ObjectMap<>();
			populateVariableAccessStates(method, accessStates);

			Set<Integer> loopModifiedSlots = Collections.emptySet();
			Set<Integer> handlerReachable = findHandlerReachable(method);

			Frame<ReValue>[] frames = context.analyze(node, method);
			for (int i = size - 1; i >= 0; i--) {
				AbstractInsnNode insn = instructions.get(i);
				int op = insn.getOpcode();

				Frame<ReValue> frame = frames[i];
				if (frame == null)
					continue;

				if (isVarLoad(op) && insn instanceof VarInsnNode vin) {

					if (handlerReachable.contains(i))
						continue;

					if (loopModifiedSlots.contains(vin.var))
						continue;

					ReValue val = frame.getLocal(vin.var);
					if (val != null && val.hasKnownValue()) {
						AbstractInsnNode replacement = OpaqueConstantFoldingTransformer.toInsn(val);
						if (replacement != null) {
							instructions.set(insn, replacement);
							dirty = true;
						}
					}
				} else {

					int var;
					Type type;
					if (insn instanceof VarInsnNode vin) {
						var = vin.var;
						type = getTypeForVarInsn(vin);
					} else if (insn instanceof IincInsnNode iinc) {
						var = iinc.var;
						type = Type.INT_TYPE;
					} else {
						continue;
					}

					Set<Integer> liveAfter = outLive.get(i);
					if (!liveAfter.contains(var) && !isProtectedByTryCatch(method, insn)) {
						if (op == IINC) {
							instructions.set(insn, new InsnNode(NOP));
						} else {
							AbstractInsnNode prev = insn.getPrevious();
							if (OpaqueConstantFoldingTransformer.isSupportedValueProducer(prev)) {
								instructions.set(prev, new InsnNode(NOP));
								instructions.set(insn, new InsnNode(NOP));
							} else {
								instructions.set(insn, new InsnNode(type.getSize() == 2 ? POP2 : POP));
							}
						}
						dirty = true;
					}
				}
			}

			int[] keys = accessStates.keys();
			for (int keyY : keys) {
				LocalAccessState stateY = accessStates.get(keyY);
				int slotY = slotFromKey(keyY);
				int typeSort = typeSortFromKey(keyY);

				List<LocalAccess> writesY = new ArrayList<>(stateY.getWrites());
				for (LocalAccess writeAccessY : writesY) {
					if (writeAccessY.offset < 0 || writeAccessY.offset >= instructions.size())
						continue;

					AbstractInsnNode writeInsnY = writeAccessY.instruction;
					if (instructions.get(writeAccessY.offset) != writeInsnY)
						continue;
					if (!(writeInsnY instanceof VarInsnNode vinY && isVarStore(vinY.getOpcode())))
						continue;

					AbstractInsnNode prevInsn = writeInsnY.getPrevious();
					if (!(prevInsn instanceof VarInsnNode vinX && isVarLoad(vinX.getOpcode())))
						continue;

					int slotX = vinX.var;
					int keyX = key(slotX, typeSort);
					if (slotX == slotY || !accessStates.containsKey(keyX))
						continue;

					Set<Integer> foldableReads = new HashSet<>();
					if (!isRedundantStore(accessStates, instructions, successorMap, writeAccessY.offset, slotX, slotY, typeSort, foldableReads))
						continue;

					replaceRedundantVariableUsage(instructions, slotX, slotY, typeSort, foldableReads);

					if (stateY.writes != null)
						stateY.writes.remove(writeAccessY);
					if (stateY.reads != null)
						stateY.reads.removeIf(access -> foldableReads.contains(access.offset));

					Type varType = Types.fromSort(typeSort);
					instructions.set(writeInsnY, new InsnNode(varType.getSize() == 2 ? POP2 : POP));
					dirty = true;
				}
			}
			dirty |= removeUnusedLocals(method);
		}
		if (dirty)
			markChange();
	}

	private static boolean removeUnusedLocals(MethodNode method) {
		InsnList instructions = method.instructions;
		if (instructions == null || instructions.size() == 0)
			return false;

		boolean dirty = false;
		while (true) {
			Int2ObjectMap<LocalAccessState> accessStates = new Int2ObjectMap<>();
			populateVariableAccessStates(method, accessStates);

			boolean roundDirty = false;
			for (int stateKey : accessStates.keys()) {
				LocalAccessState state = accessStates.get(stateKey);

				boolean hasLoad = false;
				for (LocalAccess read : state.getReads()) {
					if (read.offset >= 0 && read.offset < instructions.size()
							&& instructions.get(read.offset) == read.instruction
							&& read.instruction instanceof VarInsnNode variable
							&& isVarLoad(variable.getOpcode())) {
						hasLoad = true;
						break;
					}
				}
				if (hasLoad)
					continue;

				for (LocalAccess write : state.getWrites()) {
					if (write.offset < 0 || write.offset >= instructions.size())
						continue;

					AbstractInsnNode writeInsn = write.instruction;
					if (instructions.get(write.offset) != writeInsn)
						continue;

					if (writeInsn instanceof IincInsnNode) {
						instructions.set(writeInsn, new InsnNode(NOP));
						roundDirty = true;
					} else if (writeInsn instanceof VarInsnNode store && isVarStore(store.getOpcode())) {
						Type type = getTypeForVarInsn(store);

						AbstractInsnNode predecessor = store.getPrevious();
						while (predecessor != null && predecessor.getOpcode() == NOP)
							predecessor = predecessor.getPrevious();
						if (predecessor != null && (isConstantForStore(predecessor, store) || isMatchingLoad(type.getSort(), predecessor.getOpcode()))) {
							instructions.set(predecessor, new InsnNode(NOP));
							instructions.set(writeInsn, new InsnNode(NOP));
						} else {
							instructions.set(writeInsn, new InsnNode(type.getSize() == 2 ? POP2 : POP));
						}

						roundDirty = true;
					}
				}
			}

			if (!roundDirty)
				break;
			dirty = true;

			List<TryCatchBlockNode> tryCatchBlocks = method.tryCatchBlocks;
			if (tryCatchBlocks != null && !tryCatchBlocks.isEmpty()) {
				tryCatchBlocks.removeIf(block -> {
					for (AbstractInsnNode current = block.start; current != null && current != block.end; current = current.getNext()) {
						int opcode = current.getOpcode();
						if (opcode >= 0 && opcode != NOP)
							return false;
					}
					return true;
				});
			}
		}
		return dirty;
	}

	private static void populateLiveness(MethodNode method,
	                                     List<Set<Integer>> inLive,
	                                     List<Set<Integer>> outLive,
	                                     Int2ObjectMap<List<Integer>> successorMap,
	                                     Int2ObjectMap<List<Integer>> predecessorMap) {

		InsnList instructions = method.instructions;
		int size = instructions.size();
		for (int i = 0; i < size; i++) {
			inLive.add(new HashSet<>());
			outLive.add(new HashSet<>());
		}

		Deque<Integer> unprocessed = new ArrayDeque<>();
		for (int i = 0; i < size; i++)
			unprocessed.add(i);

		while (!unprocessed.isEmpty()) {

			int i = unprocessed.poll();
			AbstractInsnNode insn = instructions.get(i);
			int op = insn.getOpcode();

			Set<Integer> out = outLive.get(i);
			out.clear();
			for (int s : successorMap.getOrDefault(i, emptyList()))
				out.addAll(inLive.get(s));

			Set<Integer> gen = new HashSet<>();
			Set<Integer> kill = new HashSet<>();
			int var;
			if (insn instanceof VarInsnNode vin) {
				var = vin.var;
				if (isVarLoad(op)) gen.add(var);
				if (isVarStore(op)) kill.add(var);
			} else if (insn instanceof IincInsnNode iinc) {

				var = iinc.var;
				gen.add(var);
				kill.add(var);
			}

			Set<Integer> newIn = new HashSet<>(gen);
			Set<Integer> temp = new HashSet<>(out);
			temp.removeAll(kill);
			newIn.addAll(temp);

			if (!newIn.equals(inLive.get(i))) {
				inLive.set(i, newIn);
				unprocessed.addAll(predecessorMap.getOrDefault(i, emptyList()));
			}
		}
	}

	private static void populateVariableAccessStates(MethodNode method, Int2ObjectMap<LocalAccessState> accessStates) {
		InsnList instructions = method.instructions;
		int size = instructions.size();
		boolean isStatic = (method.access & ACC_STATIC) != 0;
		int paramSlot = isStatic ? 0 : 1;

		if (!isStatic) {
			LocalAccessState thisState = new LocalAccessState(0);
			thisState.addWrite(-1, new VarInsnNode(ASTORE, 0));
			accessStates.put(key(0, Type.OBJECT), thisState);
		}

		for (Type argType : Type.getArgumentTypes(method.desc)) {
			LocalAccessState paramState = new LocalAccessState(paramSlot);
			paramState.addWrite(-1, createVarStore(paramSlot, argType));
			accessStates.put(key(paramSlot, argType.getSort()), paramState);
			paramSlot += argType.getSize();
		}

		for (int i = 0; i < size; i++) {
			AbstractInsnNode insn = instructions.get(i);
			int op = insn.getOpcode();
			if (insn instanceof VarInsnNode vin) {

				Type type = getTypeForVarInsn(vin);
				LocalAccessState state = accessStates.computeIfAbsent(key(vin.var, type.getSort()), _ -> new LocalAccessState(vin.var));
				if (isVarLoad(op))
					state.addRead(i, vin);
				else if (isVarStore(op))
					state.addWrite(i, vin);
			} else if (op == IINC && insn instanceof IincInsnNode iinc) {

				LocalAccessState state = accessStates.computeIfAbsent(key(iinc.var, Type.INT), _ -> new LocalAccessState(iinc.var));
				state.addRead(i, iinc);
				state.addWrite(i, iinc);
			}
		}
	}

	private static Set<Integer> findLoopModifiedSlots(InsnList instructions,
	                                                  Int2ObjectMap<List<Integer>> successorMap,
	                                                  Int2ObjectMap<List<Integer>> predecessorMap) {
		Set<Integer> result = new HashSet<>();
		int size = instructions.size();
		for (int from = 0; from < size; from++) {
			for (int target : successorMap.getOrDefault(from, emptyList())) {
				if (target < 0 || target > from)
					continue;

				Set<Integer> loopNodes = new HashSet<>();
				Deque<Integer> pending = new ArrayDeque<>();
				loopNodes.add(target);
				loopNodes.add(from);
				pending.add(from);
				while (!pending.isEmpty()) {
					int current = pending.removeFirst();
					for (int predecessor : predecessorMap.getOrDefault(current, emptyList())) {
						if (predecessor >= target && loopNodes.add(predecessor))
							pending.addLast(predecessor);
					}
				}

				for (int nodeIndex : loopNodes) {
					AbstractInsnNode instruction = instructions.get(nodeIndex);
					if (instruction instanceof VarInsnNode variable && isVarStore(variable.getOpcode()))
						result.add(variable.var);
					else if (instruction instanceof IincInsnNode increment)
						result.add(increment.var);
				}
			}
		}
		return result;
	}

	private static boolean isProtectedByTryCatch(MethodNode method, AbstractInsnNode target) {
		if (method.tryCatchBlocks == null)
			return false;
		for (TryCatchBlockNode block : method.tryCatchBlocks)
			for (AbstractInsnNode current = block.start; current != null && current != block.end; current = current.getNext())
				if (current == target)
					return true;
		return false;
	}

	private static Set<Integer> findHandlerReachable(MethodNode method) {
		if (method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty())
			return Collections.emptySet();

		Int2ObjectMap<List<Integer>> successors = new Int2ObjectMap<>();
		populateFlowMaps(method, successors, new Int2ObjectMap<>(), false);
		Deque<Integer> pending = new ArrayDeque<>();
		Set<Integer> reachable = new HashSet<>();
		for (TryCatchBlockNode block : method.tryCatchBlocks) {
			int handlerIndex = method.instructions.indexOf(block.handler);
			if (handlerIndex >= 0 && reachable.add(handlerIndex))
				pending.addLast(handlerIndex);
		}
		while (!pending.isEmpty()) {
			int current = pending.removeFirst();
			for (int successor : successors.getOrDefault(current, emptyList()))
				if (reachable.add(successor))
					pending.addLast(successor);
		}
		return reachable;
	}

	private static boolean foldSingleConstantLocals(String owner, MethodNode method) {
		InsnList instructions = method.instructions;
		if (instructions == null || instructions.size() == 0)
			return false;

		Int2ObjectMap<LocalAccessState> accessStates = new Int2ObjectMap<>();
		populateVariableAccessStates(method, accessStates);

		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
		} catch (Throwable t) {

			return false;
		}

		boolean dirty = false;
		Set<Integer> handlerReachable = findHandlerReachable(method);
		for (int key : accessStates.keys()) {
			LocalAccessState state = accessStates.get(key);
			NavigableSet<LocalAccess> writes = state.getWrites();
			NavigableSet<LocalAccess> reads = state.getReads();
			if (writes.size() != 1 || reads.isEmpty())
				continue;

			LocalAccess write = writes.first();
			if (write.offset < 0
					|| !(write.instruction instanceof VarInsnNode store)
					|| !isVarStore(store.getOpcode()))
				continue;

			int storeIndex = write.offset;
			if (storeIndex >= frames.length || frames[storeIndex] == null)
				continue;

			Frame<SourceValue> frame = frames[storeIndex];
			if (frame.getStackSize() == 0)
				continue;

			SourceValue source = frame.getStack(frame.getStackSize() - 1);
			if (source == null || source.insns.size() != 1)
				continue;

			AbstractInsnNode producer = source.insns.iterator().next();
			if (!isConstantForStore(producer, store))
				continue;

			boolean ordered = true;
			for (LocalAccess read : reads) {
				if (read.offset <= storeIndex
						|| !(read.instruction instanceof VarInsnNode load)
						|| !isVarLoad(load.getOpcode())) {
					ordered = false;
					break;
				}
			}
			if (!ordered)
				continue;

			List<LocalAccess> foldableReads = new ArrayList<>();
			for (LocalAccess read : reads) {
				if (!handlerReachable.contains(read.offset))
					foldableReads.add(read);
			}
			if (foldableReads.isEmpty())
				continue;

			for (LocalAccess read : foldableReads)
				instructions.set(read.instruction, producer.clone(Collections.emptyMap()));
			dirty = true;
		}
		return dirty;
	}

	private static boolean isConstantForStore(AbstractInsnNode producer, VarInsnNode store) {
		int producerOp = producer.getOpcode();
		int storeOp = store.getOpcode();
		if (!isConstValue(producerOp))
			return false;
		return switch (storeOp) {
			case ISTORE -> isConstIntValue(producer);
			case FSTORE ->
					producerOp >= FCONST_0 && producerOp <= FCONST_2 || producer instanceof LdcInsnNode ldc && ldc.cst instanceof Float;
			case LSTORE ->
					producerOp >= LCONST_0 && producerOp <= LCONST_1 || producer instanceof LdcInsnNode ldc && ldc.cst instanceof Long;
			case DSTORE ->
					producerOp >= DCONST_0 && producerOp <= DCONST_1 || producer instanceof LdcInsnNode ldc && ldc.cst instanceof Double;
			case ASTORE ->
					producerOp == ACONST_NULL || producer instanceof LdcInsnNode ldc && ldc.cst instanceof String;
			default -> false;
		};
	}

	private static boolean isRedundantStore(Int2ObjectMap<LocalAccessState> accessStates,
	                                        InsnList instructions,
	                                        Int2ObjectMap<List<Integer>> successorMap,
	                                        int storeIndexY, int slotX, int slotY, int typeSort,
	                                        Set<Integer> foldableReads) {
		foldableReads.clear();
		LocalAccessState stateX = accessStates.get(key(slotX, typeSort));
		if (stateX == null)
			return false;

		AbstractInsnNode storeInsnY = instructions.get(storeIndexY);
		AbstractInsnNode prev = storeInsnY.getPrevious();
		if (!(prev instanceof VarInsnNode vinX && vinX.var == slotX && isMatchingLoad(typeSort, vinX.getOpcode())))
			return false;

		int sourceSize = Types.fromSort(typeSort).getSize();
		int targetSize = sourceSize;
		for (int j = instructions.indexOf(prev) + 1; j < storeIndexY; j++) {
			AbstractInsnNode ins = instructions.get(j);
			if (isOverlappingVariableWrite(ins, slotX, sourceSize) || isOverlappingVariableWrite(ins, slotY, targetSize))
				return false;
		}

		NavigableSet<LocalAccess> writesX = stateX.getWrites();
		if (writesX.isEmpty())
			return false;
		if (writesX.first().offset >= storeIndexY)
			return false;

		final int validState = 1;
		final int sourceChangedState = 1 << 1;
		final int targetKilledState = 1 << 2;
		final int bypassedStoreState = 1 << 3;
		int size = instructions.size();
		int[] state = new int[size];

		Deque<Integer> unprocessed = new ArrayDeque<>();
		state[0] = bypassedStoreState;
		unprocessed.add(0);

		while (!unprocessed.isEmpty()) {
			int i = unprocessed.poll();
			AbstractInsnNode insn = instructions.get(i);
			int op = insn.getOpcode();
			int currentState = state[i];

			if (i == storeIndexY) {
				for (int s : successorMap.getOrDefault(i, emptyList())) {
					int oldState = state[s];
					int newState = oldState | validState;
					if (newState != oldState) {
						state[s] = newState;
						unprocessed.add(s);
					}
				}
				continue;
			}

			boolean copyIsValid = (currentState & validState) != 0;
			boolean sourceChanged = (currentState & sourceChangedState) != 0;
			boolean targetKilled = (currentState & targetKilledState) != 0;
			boolean bypassedStore = (currentState & bypassedStoreState) != 0;

			if (typeSort == Type.INT && insn instanceof IincInsnNode iinc && iinc.var == slotY) {
				if (bypassedStore || sourceChanged || (copyIsValid && targetKilled))
					return false;
				if (copyIsValid)
					foldableReads.add(i);

				for (int s : successorMap.getOrDefault(i, emptyList())) {
					int oldState = state[s];
					int newState = oldState | targetKilledState;
					if (newState != oldState) {
						state[s] = newState;
						unprocessed.add(s);
					}
				}
				continue;
			}

			if (isOverlappingVariableWrite(insn, slotY, targetSize)) {
				for (int s : successorMap.getOrDefault(i, emptyList())) {
					int oldState = state[s];
					int newState = oldState | targetKilledState;
					if (newState != oldState) {
						state[s] = newState;
						unprocessed.add(s);
					}
				}
				continue;
			}

			if (insn instanceof VarInsnNode variable && variable.var == slotY && isMatchingLoad(typeSort, op)) {
				if (bypassedStore || sourceChanged || (copyIsValid && targetKilled))
					return false;
				if (copyIsValid)
					foldableReads.add(i);
			}

			int newState = currentState;
			if (copyIsValid && isOverlappingVariableWrite(insn, slotX, sourceSize))
				newState = (newState & ~validState) | sourceChangedState;
			for (int s : successorMap.getOrDefault(i, emptyList())) {
				int oldState = state[s];
				int newStatePropagated = oldState | newState;
				if (newStatePropagated != oldState) {
					state[s] = newStatePropagated;
					unprocessed.add(s);
				}
			}
		}

		return !foldableReads.isEmpty();
	}

	private static boolean isOverlappingVariableWrite(AbstractInsnNode instruction, int slot, int size) {
		int end = slot + size;
		if (instruction instanceof VarInsnNode variable && isVarStore(variable.getOpcode())) {
			int variableEnd = variable.var + getTypeForVarInsn(variable).getSize();
			return variable.var < end && variableEnd > slot;
		}
		return instruction instanceof IincInsnNode iinc
				&& iinc.var >= slot
				&& iinc.var < end;
	}

	private static void replaceRedundantVariableUsage(InsnList instructions, int slotX, int slotY, int typeSort,
	                                                  Set<Integer> foldableReads) {
		AbstractInsnNode replacement = createVarLoad(slotX, typeSort);
		for (int offset : foldableReads) {
			if (offset < 0 || offset >= instructions.size())
				continue;
			AbstractInsnNode insn = instructions.get(offset);
			if (insn instanceof VarInsnNode vin && vin.var == slotY && isMatchingLoad(typeSort, vin.getOpcode())) {
				instructions.set(insn, replacement.clone(null));
			} else if (typeSort == Type.INT && insn instanceof IincInsnNode iinc && iinc.var == slotY) {
				iinc.var = slotX;
			}
		}
	}

	private static boolean isMatchingLoad(int typeSort, int opcode) {
		return switch (typeSort) {
			case Type.INT -> opcode == ILOAD;
			case Type.FLOAT -> opcode == FLOAD;
			case Type.LONG -> opcode == LLOAD;
			case Type.DOUBLE -> opcode == DLOAD;
			case Type.OBJECT, Type.ARRAY -> opcode == ALOAD;
			default -> false;
		};
	}

	private static int key(int slot, int typeSort) {
		int normalizedSort = Types.getNormalizedSort(typeSort);
		return slot | (normalizedSort << 16);
	}

	private static int slotFromKey(int key) {
		return key & 0xFFFF;
	}

	private static int typeSortFromKey(int key) {
		return key >> 16;
	}

	private static class LocalAccessState {
		private final int index;
		private NavigableSet<LocalAccess> reads;
		private NavigableSet<LocalAccess> writes;

		private LocalAccessState(int index) {
			this.index = index;
		}

		public void addRead(int offset, AbstractInsnNode instruction) {
			if (reads == null)
				reads = new TreeSet<>();
			reads.add(new LocalAccess(offset, instruction));
		}

		public void addWrite(int offset, AbstractInsnNode instruction) {
			if (writes == null)
				writes = new TreeSet<>();
			writes.add(new LocalAccess(offset, instruction));
		}

		public NavigableSet<LocalAccess> getReads() {
			if (reads == null)
				return emptyNavigableSet();
			return reads;
		}

		public NavigableSet<LocalAccess> getWrites() {
			if (writes == null)
				return emptyNavigableSet();
			return writes;
		}

		@Override
		public String toString() {
			return "LocalAccessState{" +
					"index=" + index +
					", reads=" + reads +
					", writes=" + writes +
					'}';
		}
	}

	private record LocalAccess(int offset, AbstractInsnNode instruction) implements Comparable<LocalAccess> {
		@Override
		public int compareTo(LocalAccess o) {
			return Integer.compare(offset, o.offset);
		}
	}
}

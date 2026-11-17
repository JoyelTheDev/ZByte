package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import java.util.*;

import dev.joyel.zbyte.reanalysis.*;
import dev.joyel.zbyte.reanalysis.value.*;
import dev.joyel.zbyte.utils.AsmFlowUtil;
import java.util.Collections;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;

import static dev.joyel.zbyte.utils.AsmFlowUtil.*;
import static dev.joyel.zbyte.transformers.universal.OpaqueConstantFoldingTransformer.isSupportedValueProducer;
import static org.objectweb.asm.Opcodes.*;

public class OpaquePredicateFoldingTransformer extends Transformer<TransformerConfig> {
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
		for (MethodNode method : node.methods) {
			InsnList instructions = method.instructions;

			if (instructions == null)
				continue;

			for (int i = 1; i < instructions.size() - 1; i++) {
				AbstractInsnNode instruction = instructions.get(i);
				if (instruction instanceof TableSwitchInsnNode switchInsn && isSwitchEffectiveGoto(switchInsn)) {
					AbstractInsnNode previous = switchInsn.getPrevious();

					if (previous.getOpcode() != DUP2 && isValueProducerOrTopDup(previous))
						instructions.remove(previous);
					else
						instructions.insertBefore(switchInsn, new InsnNode(POP));

					instructions.set(switchInsn, new JumpInsnNode(GOTO, switchInsn.dflt));
					dirty = true;
				} else if (instruction instanceof LookupSwitchInsnNode switchInsn && isSwitchEffectiveGoto(switchInsn)) {
					AbstractInsnNode previous = switchInsn.getPrevious();

					if (previous.getOpcode() != DUP2 && isValueProducerOrTopDup(previous))
						instructions.remove(previous);
					else
						instructions.insertBefore(switchInsn, new InsnNode(POP));

					instructions.set(switchInsn, new JumpInsnNode(GOTO, switchInsn.dflt));
					dirty = true;
				}
			}

			try {
				boolean localDirty;
				do {
					localDirty = false;

					ReAnalyzer analyzer = context.newAnalyzer(node, method);
					analyzer.setPruneOutputDeadCodeFrames(false);
					Frame<ReValue>[] frames;
					try {
						frames = analyzer.analyze(node.name, method);
					} catch (Throwable t) {

						break;
					}

					for (int i = 1; i < instructions.size() - 1; i++) {
						AbstractInsnNode instruction = instructions.get(i);

						if (!isFlowControl(instruction))
							continue;

						if (instruction.getOpcode() == GOTO)
							continue;

						if (i >= frames.length)
							continue;
						Frame<ReValue> frame = frames[i];
						if (frame == null || frame.getStackSize() == 0)
							continue;

						ReValue stackTop = frame.getStack(frame.getStackSize() - 1);
						if (!stackTop.hasKnownValue() && !(stackTop instanceof ObjectValue ov && ov.isNull()))
							continue;

						AbstractInsnNode prevInstruction = AsmFlowUtil.getPreviousInsn(instruction);
						if (prevInstruction == null
								|| !isValueProducerOrTopDup(prevInstruction)
								|| hasControlBoundaryBetween(method, prevInstruction, instruction))
							continue;

						int insnType = instruction.getType();
						if (insnType == AbstractInsnNode.JUMP_INSN) {
							JumpInsnNode jin = (JumpInsnNode) instruction;
							int opcode = instruction.getOpcode();
							if ((opcode >= IFEQ && opcode <= IFLE) || opcode == IFNULL || opcode == IFNONNULL) {

								localDirty = switch (opcode) {
									case IFEQ ->
											replaceIntValue(instructions, prevInstruction, stackTop, jin, v -> v.isEqualTo(0));
									case IFNE ->
											replaceIntValue(instructions, prevInstruction, stackTop, jin, v -> v.isNotEqualTo(0));
									case IFLT ->
											replaceIntValue(instructions, prevInstruction, stackTop, jin, v -> v.isLessThan(0));
									case IFGE ->
											replaceIntValue(instructions, prevInstruction, stackTop, jin, v -> v.isGreaterThanOrEqual(0));
									case IFGT ->
											replaceIntValue(instructions, prevInstruction, stackTop, jin, v -> v.isGreaterThan(0));
									case IFLE ->
											replaceIntValue(instructions, prevInstruction, stackTop, jin, v -> v.isLessThanOrEqual(0));
									case IFNULL ->
											replaceObjValue(instructions, prevInstruction, stackTop, jin, ObjectValue::isNull);
									case IFNONNULL ->
											replaceObjValue(instructions, prevInstruction, stackTop, jin, ObjectValue::isNotNull);
									default -> localDirty;
								};
							} else if (opcode >= IF_ICMPEQ && opcode <= IF_ACMPNE) {

								if (frame.getStackSize() < 2)
									continue;
								ReValue stack2ndTop = frame.getStack(frame.getStackSize() - 2);
								if (!stack2ndTop.hasKnownValue() && !(stack2ndTop instanceof ObjectValue ov && ov.isNull()))
									continue;

								AbstractInsnNode prevPrevInstruction = prevInstruction.getPrevious();
								if (prevPrevInstruction == null
										|| !isValueProducerOrTopDup(prevPrevInstruction)
										|| hasControlBoundaryBetween(method, prevInstruction, instruction))
									continue;

								localDirty = switch (opcode) {
									case IF_ICMPEQ ->
											replaceIntIntValue(instructions, prevPrevInstruction, prevInstruction, stack2ndTop, stackTop, jin, IntValue::isEqualTo);
									case IF_ICMPNE ->
											replaceIntIntValue(instructions, prevPrevInstruction, prevInstruction, stack2ndTop, stackTop, jin, IntValue::isNotEqualTo);
									case IF_ICMPLT ->
											replaceIntIntValue(instructions, prevPrevInstruction, prevInstruction, stack2ndTop, stackTop, jin, IntValue::isLessThan);
									case IF_ICMPGE ->
											replaceIntIntValue(instructions, prevPrevInstruction, prevInstruction, stack2ndTop, stackTop, jin, IntValue::isGreaterThanOrEqual);
									case IF_ICMPGT ->
											replaceIntIntValue(instructions, prevPrevInstruction, prevInstruction, stack2ndTop, stackTop, jin, IntValue::isGreaterThan);
									case IF_ICMPLE ->
											replaceIntIntValue(instructions, prevPrevInstruction, prevInstruction, stack2ndTop, stackTop, jin, IntValue::isLessThanOrEqual);
									case IF_ACMPEQ ->
											replaceObjObjValue(instructions, prevPrevInstruction, prevInstruction, stack2ndTop, stackTop, jin,
													(a, b) -> a.isNull() && b.isNull(),
													(a, b) -> (a.isNull() && b.isNotNull()) || (a.isNotNull() && b.isNull()));
									case IF_ACMPNE ->
											replaceObjObjValue(instructions, prevPrevInstruction, prevInstruction, stack2ndTop, stackTop, jin,
													(a, b) -> (a.isNull() && b.isNotNull()) || (a.isNotNull() && b.isNull()),
													(a, b) -> a.isNull() && b.isNull());
									default -> localDirty;
								};
							}
						} else if (insnType == AbstractInsnNode.LOOKUPSWITCH_INSN) {
							LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) instruction;

							if (!(stackTop instanceof IntValue intValue))
								continue;

							int keyIndex = -1;
							for (int j = 0; j < lsin.keys.size(); j++) {
								int key = lsin.keys.get(j);
								if (intValue.isEqualTo(key)) {
									keyIndex = j;
									break;
								}
							}

							JumpInsnNode replacement = keyIndex == -1 ?
									new JumpInsnNode(GOTO, lsin.dflt) :
									new JumpInsnNode(GOTO, lsin.labels.get(keyIndex));
							removeSingleValueProducer(instructions, prevInstruction, lsin);
							instructions.set(lsin, replacement);
							localDirty = true;
						} else if (insnType == AbstractInsnNode.TABLESWITCH_INSN) {
							TableSwitchInsnNode tsin = (TableSwitchInsnNode) instruction;

							if (!(stackTop instanceof IntValue intValue))
								continue;

							int arg = intValue.value().getAsInt();
							int keyIndex = (arg > tsin.max || arg < tsin.min) ?
									-1 : (arg - tsin.min);

							JumpInsnNode replacement = keyIndex == -1 ?
									new JumpInsnNode(GOTO, tsin.dflt) :
									new JumpInsnNode(GOTO, tsin.labels.get(keyIndex));
							removeSingleValueProducer(instructions, prevInstruction, tsin);
							instructions.set(tsin, replacement);
							localDirty = true;
						}

						if (localDirty)
							break;
					}

					if (localDirty) {
						context.pruneDeadCode(node, method);
						dirty = true;
					}
				} while (localDirty);
			} catch (Throwable t) {
				throw new TransformationException("Error encountered when folding opaque predicates", t);
			}
		}
		if (dirty)
			markChange();
	}

	private static boolean hasControlBoundaryBetween(MethodNode method,
	                                                 AbstractInsnNode producer,
	                                                 AbstractInsnNode consumer) {

		for (AbstractInsnNode current = producer.getNext(); current != null
				&& current != consumer; current = current.getNext()) {

			if (!(current instanceof LabelNode label))
				continue;

			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof JumpInsnNode jump && jump.label == label)
					return true;
				if (instruction instanceof TableSwitchInsnNode table
						&& (table.dflt == label || table.labels.contains(label)))
					return true;
				if (instruction instanceof LookupSwitchInsnNode lookup
						&& (lookup.dflt == label || lookup.labels.contains(label)))
					return true;
			}
		}
		return producer.getNext() == null;
	}

	private static boolean replaceIntValue(InsnList instructions,
	                                       AbstractInsnNode stackValueProducerInsn,
	                                       ReValue stackTopValue,
	                                       JumpInsnNode jump,
	                                       Predicate<IntValue> gotoCondition) {
		if (stackTopValue instanceof IntValue intValue) {

			removeSingleValueProducer(instructions, stackValueProducerInsn, jump);

			AbstractInsnNode replacement = gotoCondition.test(intValue) ?
					new JumpInsnNode(GOTO, jump.label) :
					new InsnNode(NOP);
			instructions.set(jump, replacement);
			return true;
		}
		return false;
	}

	private static boolean replaceIntIntValue(InsnList instructions,
	                                          AbstractInsnNode stackValueProducerInsnA,
	                                          AbstractInsnNode stackValueProducerInsnB,
	                                          ReValue stackTopValueA,
	                                          ReValue stackTopValueB,
	                                          JumpInsnNode jump,
	                                          BiPredicate<IntValue, IntValue> gotoCondition) {
		if (stackTopValueA instanceof IntValue intValueA && stackTopValueB instanceof IntValue intValueB) {
			AbstractInsnNode replacement = gotoCondition.test(intValueA, intValueB) ?
					new JumpInsnNode(GOTO, jump.label) :
					new InsnNode(NOP);
			instructions.set(jump, replacement);

			if (stackValueProducerInsnB.getOpcode() == DUP2) {
				instructions.set(stackValueProducerInsnB, new InsnNode(NOP));
			} else if (stackValueProducerInsnA.getOpcode() == DUP2) {
				instructions.insertBefore(stackValueProducerInsnB, new InsnNode(POP));
				instructions.set(stackValueProducerInsnB, new InsnNode(NOP));
			} else {
				instructions.set(stackValueProducerInsnA, new InsnNode(NOP));
				instructions.set(stackValueProducerInsnB, new InsnNode(NOP));
			}

			return true;
		}
		return false;
	}

	private static boolean replaceObjValue(InsnList instructions,
	                                       AbstractInsnNode stackValueProducerInsn,
	                                       ReValue stackTopValue,
	                                       JumpInsnNode jump,
	                                       Predicate<ObjectValue> gotoCondition) {
		if (stackTopValue instanceof ObjectValue objectValue) {

			removeSingleValueProducer(instructions, stackValueProducerInsn, jump);

			AbstractInsnNode replacement = gotoCondition.test(objectValue) ?
					new JumpInsnNode(GOTO, jump.label) :
					new InsnNode(NOP);
			instructions.set(jump, replacement);
			return true;
		}
		return false;
	}

	private static boolean replaceObjObjValue(InsnList instructions,
	                                          AbstractInsnNode stackValueProducerInsnA,
	                                          AbstractInsnNode stackValueProducerInsnB,
	                                          ReValue stackTopValueA,
	                                          ReValue stackTopValueB,
	                                          JumpInsnNode jump,
	                                          BiPredicate<ObjectValue, ObjectValue> gotoCondition,
	                                          BiPredicate<ObjectValue, ObjectValue> fallCondition) {
		if (stackTopValueA instanceof ObjectValue objValueA && stackTopValueB instanceof ObjectValue objValueB) {

			AbstractInsnNode replacement = gotoCondition.test(objValueA, objValueB) ? new JumpInsnNode(GOTO, jump.label) : null;
			if (replacement == null) replacement = fallCondition.test(objValueA, objValueB) ? new InsnNode(NOP) : null;
			if (replacement == null) return false;
			instructions.set(jump, replacement);

			if (stackValueProducerInsnB.getOpcode() == DUP2) {
				instructions.set(stackValueProducerInsnB, new InsnNode(NOP));
			} else if (stackValueProducerInsnA.getOpcode() == DUP2) {
				instructions.insertBefore(stackValueProducerInsnB, new InsnNode(POP));
				instructions.set(stackValueProducerInsnB, new InsnNode(NOP));
			} else {
				instructions.set(stackValueProducerInsnA, new InsnNode(NOP));
				instructions.set(stackValueProducerInsnB, new InsnNode(NOP));
			}

			return true;
		}
		return false;
	}

	private static void removeSingleValueProducer(InsnList instructions,
	                                              AbstractInsnNode producer,
	                                              AbstractInsnNode consumer) {
		if (producer.getOpcode() == DUP2)
			instructions.insertBefore(consumer, new InsnNode(POP));
		else
			instructions.set(producer, new InsnNode(NOP));
	}

	private static boolean isValueProducerOrTopDup(AbstractInsnNode insnNode) {
		if (isSupportedValueProducer(insnNode))
			return true;

		int op = insnNode.getOpcode();
		return op == DUP || op == DUP2;
	}
}

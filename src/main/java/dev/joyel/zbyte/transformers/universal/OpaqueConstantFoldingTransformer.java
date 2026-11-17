package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import java.util.*;

import dev.joyel.zbyte.reanalysis.AnalysisSupport;
import dev.joyel.zbyte.reanalysis.ReFrame;
import dev.joyel.zbyte.reanalysis.TransformationException;
import dev.joyel.zbyte.reanalysis.Types;
import dev.joyel.zbyte.reanalysis.lookup.*;
import dev.joyel.zbyte.reanalysis.value.*;
import dev.joyel.zbyte.utils.AsmFlowUtil;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;

import static dev.joyel.zbyte.utils.AsmFlowUtil.*;
import static org.objectweb.asm.Opcodes.*;

public class OpaqueConstantFoldingTransformer extends Transformer<TransformerConfig> {
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
	private static final int[] ARG_1_SIZE = new int[255];
	private static final int[] ARG_2_SIZE = new int[255];
	private static final GetStaticLookup STATIC_FIELD_LOOKUP = NoopLookups.GET_STATIC;
	private static final InvokeStaticLookup STATIC_METHOD_LOOKUP = NoopLookups.INVOKE_STATIC;
	private static final InvokeVirtualLookup VIRTUAL_METHOD_LOOKUP = NoopLookups.INVOKE_VIRTUAL;
	private AnalysisSupport context;
	private boolean inlineStringConstructors;

	public OpaqueConstantFoldingTransformer() {
		this(false);
	}

	public OpaqueConstantFoldingTransformer(boolean inlineStringConstructors) {
		this.inlineStringConstructors = inlineStringConstructors;
	}

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
			try {

				dirty |= pass1StackManipulation(context, node, method, instructions);
				dirty |= pass2SequenceFolding(context, node, method, instructions);

				if (dirty) {
					for (AbstractInsnNode insn : instructions.toArray()) {
						if (insn.getOpcode() == NOP)
							instructions.remove(insn);
					}
				}
			} catch (Throwable t) {
				throw new TransformationException("Error encountered when folding constants", t);
			}
		}
		if (dirty)
			markChange();
	}

	private boolean pass1StackManipulation(AnalysisSupport context, ClassNode node,
	                                       MethodNode method, InsnList instructions) {
		boolean dirty = false;
		int insertions = 0;
		Frame<ReValue>[] frames;
		try {
			frames = context.analyze(node, method);
		} catch (Throwable t) {

			return false;
		}
		for (int i = 1; i < instructions.size() - 1; i++) {
			Frame<ReValue> frame = frames[i - insertions];
			if (frame == null || frame.getStackSize() == 0)
				continue;

			AbstractInsnNode instruction = instructions.get(i);
			int opcode = instruction.getOpcode();
			switch (opcode) {
				case DUP -> {
					if (getSlotsOccupied(frame) < 1)
						continue;
					ReValue top = frame.getStack(frame.getStackSize() - 1);
					AbstractInsnNode replacement = toInsn(top);
					if (replacement != null) {
						instructions.set(instruction, replacement);
						dirty = true;
					} else if (isSupportedValueProducer(instruction.getPrevious())) {
						instructions.set(instruction, instruction.getPrevious().clone(Collections.emptyMap()));
						dirty = true;
					}
				}
				case DUP2 -> {
					if (getSlotsOccupied(frame) < 2)
						continue;
					BinaryOperationArguments arguments = getBinaryOperationArguments(instruction.getPrevious(), DUP2);
					if (arguments != null && arguments.combinedIntermediates().isEmpty()) {
						if (arguments.argument2().sameAs(arguments.argument1())) {

							AbstractInsnNode arg = arguments.argument2().insn();
							if (isSupportedValueProducer(arg)) {
								instructions.set(instruction, arg.clone(Collections.emptyMap()));
								dirty = true;
							}
						} else {

							AbstractInsnNode arg1 = arguments.argument1().insn();
							AbstractInsnNode arg2 = arguments.argument2().insn();
							instructions.insert(instruction, arg2.clone(Collections.emptyMap()));
							instructions.set(instruction, arg1.clone(Collections.emptyMap()));
							insertions += 1;
							dirty = true;
						}

					}
				}
				case DUP_X1 -> {
					if (getSlotsOccupied(frame) < 2)
						continue;
					BinaryOperationArguments arguments = getBinaryOperationArguments(instruction.getPrevious(), DUP2_X1);
					if (arguments != null && arguments.combinedIntermediates().isEmpty()) {
						instructions.insertBefore(arguments.argument1().insn(), arguments.argument2().insn().clone(Collections.emptyMap()));
						instructions.set(instruction, new InsnNode(NOP));
						insertions += 1;
						dirty = true;
					}
				}
				case DUP_X2 -> {
					if (getSlotsOccupied(frame) < 3)
						continue;
					BinaryOperationArguments arguments = getBinaryOperationArguments(instruction.getPrevious(), DUP2_X1);
					if (arguments != null && arguments.combinedIntermediates().isEmpty()) {
						Argument prior = collectArgument(arguments.argument1().insn().getPrevious());
						if (prior == null)
							continue;
						instructions.insertBefore(prior.insn(), arguments.argument2().insn().clone(Collections.emptyMap()));
						instructions.set(instruction, new InsnNode(NOP));
						insertions += 1;
						dirty = true;
					}
				}
				case DUP2_X1 -> {
					if (getSlotsOccupied(frame) < 2)
						continue;
					BinaryOperationArguments arguments = getBinaryOperationArguments(instruction.getPrevious(), DUP2_X1);
					if (arguments == null || !arguments.combinedIntermediates().isEmpty())
						continue;
					Argument prior = collectArgument(arguments.argument1().insn().getPrevious());
					if (prior == null)
						continue;

					AbstractInsnNode target = prior.insn();
					if (arguments.argument2().sameAs(arguments.argument1())) {

						AbstractInsnNode arg = arguments.argument2().insn();
						if (isSupportedValueProducer(arg)) {
							instructions.insertBefore(target, arg.clone(Collections.emptyMap()));
							instructions.set(instruction, new InsnNode(NOP));
							insertions += 1;
							dirty = true;
						}
					} else {

						AbstractInsnNode arg1 = arguments.argument1().insn();
						AbstractInsnNode arg2 = arguments.argument2().insn();
						instructions.insertBefore(target, arg1.clone(Collections.emptyMap()));
						instructions.insertBefore(target, arg2.clone(Collections.emptyMap()));
						instructions.set(instruction, new InsnNode(NOP));
						insertions += 2;
						dirty = true;
					}
				}
				case DUP2_X2 -> {
					if (getSlotsOccupied(frame) < 2)
						continue;
					BinaryOperationArguments arguments = getBinaryOperationArguments(instruction.getPrevious(), DUP2_X1);
					if (arguments == null || !arguments.combinedIntermediates().isEmpty())
						continue;
					BinaryOperationArguments prior = getBinaryOperationArguments(arguments.argument1().insn().getPrevious(), DUP2_X1);
					if (prior == null || !prior.combinedIntermediates().isEmpty())
						continue;

					AbstractInsnNode target = prior.argument1().insn();
					if (arguments.argument2().sameAs(arguments.argument1())) {

						AbstractInsnNode arg = arguments.argument2().insn();
						if (isSupportedValueProducer(arg)) {
							instructions.insertBefore(target, arg.clone(Collections.emptyMap()));
							instructions.set(instruction, new InsnNode(NOP));
							insertions += 1;
							dirty = true;
						}
					} else {

						AbstractInsnNode arg1 = arguments.argument1().insn();
						AbstractInsnNode arg2 = arguments.argument2().insn();
						instructions.insertBefore(target, arg1.clone(Collections.emptyMap()));
						instructions.insertBefore(target, arg2.clone(Collections.emptyMap()));
						instructions.set(instruction, new InsnNode(NOP));
						insertions += 2;
						dirty = true;
					}
				}
				case POP -> {
					if (getSlotsOccupied(frame) < 1)
						continue;
					Argument argument = collectArgument(instruction.getPrevious());
					if (argument != null && argument.intermediates().isEmpty()) {
						instructions.set(instruction, new InsnNode(NOP));
						argument.replaceInsn(instructions);
						dirty = true;
					}
				}
				case POP2 -> {
					if (getSlotsOccupied(frame) < 2)
						continue;
					BinaryOperationArguments arguments = getBinaryOperationArguments(instruction.getPrevious(), POP2);
					if (arguments != null && arguments.combinedIntermediates().isEmpty()) {
						instructions.set(instruction, new InsnNode(NOP));
						arguments.replaceBinOp(instructions);
						dirty = true;
					}
				}
				case SWAP -> {
					if (getSlotsOccupied(frame) < 2)
						continue;
					BinaryOperationArguments arguments = getBinaryOperationArguments(instruction.getPrevious(), POP2);
					if (arguments != null && arguments.combinedIntermediates().isEmpty()) {
						if (arguments.argument1().sameAs(arguments.argument2())) {

							instructions.set(instruction, new InsnNode(NOP));
						} else {

							instructions.remove(arguments.argument1().insn());
							instructions.insert(arguments.argument2().insn(), arguments.argument1().insn());
							instructions.set(instruction, new InsnNode(NOP));
						}
						dirty = true;
					}
				}
			}
		}
		return dirty;
	}

	private boolean pass2SequenceFolding(AnalysisSupport context, ClassNode node,
	                                     MethodNode method, InsnList instructions) {
		boolean dirty = false;
		List<AbstractInsnNode> sequence = new ArrayList<>();
		Frame<ReValue>[] frames;
		try {
			frames = context.analyze(node, method);
		} catch (Throwable t) {

			return false;
		}
		int endIndex = instructions.size() - 1;
		if (removeDiscardedSequences(method, instructions, frames, sequence))
			return true;

		int unknownState = -1;
		int sequenceScanBoundary = -1;
		for (int i = 1; i < endIndex; i++) {
			AbstractInsnNode instruction = instructions.get(i);
			int opcode = instruction.getOpcode();

			if (opcode == CHECKCAST) {
				Frame<ReValue> frame = frames[i];
				if (frame != null && foldRedundantCheckcast(instructions, instruction, frame))
					dirty = true;
				continue;
			}

			Frame<ReValue> frame = frames[i];
			if (frame != null && isStringToStringCall(instruction)
					&& foldRedundantStringToString(instructions, instruction, frame)) {
				dirty = true;
				continue;
			}

			int sizeConsumed = getSizeConsumed(instruction);
			if (sizeConsumed == 0 || (opcode >= POP && opcode <= DUP2_X2))
				continue;

			boolean isReturn = isReturn(opcode) && opcode != RETURN;

			if (frame == null)
				continue;
			Frame<ReValue> nextFrame = frames[i + 1];
			if ((nextFrame == null || nextFrame.getStackSize() <= 0) && !isReturn)
				continue;

			StringValue foldedStringValue = inlineStringConstructors ? getKnownStringConstructorValue(instruction, frame) : null;
			ReValue resultValue = foldedStringValue != null ? foldedStringValue : isReturn ?
					frame.getStack(frame.getStackSize() - 1) :
					nextFrame.getStack(nextFrame.getStackSize() - 1);
			boolean resultIsKnownButNonEmittable = resultValue.hasKnownValue()
					&& !(resultValue instanceof IntValue || resultValue instanceof FloatValue
					|| resultValue instanceof DoubleValue || resultValue instanceof LongValue
					|| resultValue instanceof StringValue);
			AbstractInsnNode nextInstruction = instruction.getNext();
			boolean repeatedUnknownOperation = !resultValue.hasKnownValue()
					&& !isReturn
					&& !isLabel(nextInstruction)
					&& nextInstruction != null
					&& nextInstruction.getOpcode() == opcode;
			if (repeatedUnknownOperation) {

				if (foldRedundantOperations(instructions, instruction, frame))
					dirty = true;
				else
					unknownState = i;
				continue;
			}

			boolean validSequence = true;
			int netStackChange = 0;
			int j = i;
			boolean ignoreUnknownArrayState = foldedStringValue != null
					&& unknownState >= 0
					&& unknownState < i
					&& isArrayStore(instructions.get(unknownState).getOpcode());
			sequence.clear();
			Map<Integer, ReValue> sequenceVarWrites = new HashMap<>();
			while (j >= 0) {
				AbstractInsnNode insn = instructions.get(j);
				int insnOp = insn.getOpcode();
				if (insnOp != NOP && insnOp != -1)
					sequence.add(insn);

				boolean crossedUnknownState = j < unknownState && !ignoreUnknownArrayState;
				boolean crossedSequenceScanBoundary = resultIsKnownButNonEmittable && j < sequenceScanBoundary;
				if (crossedUnknownState || crossedSequenceScanBoundary) {

					if (crossedUnknownState)
						unknownState = i;
					if (crossedSequenceScanBoundary)
						sequenceScanBoundary = i;
					validSequence = false;
					break;
				}

				if (isFlowControl(insn)) {
					validSequence = false;
					break;
				}

				if (hasUnkeepableSideEffect(insn) && !(insn == instruction && foldedStringValue != null)) {
					validSequence = false;
					break;
				}
				if (insn.getType() == AbstractInsnNode.LABEL && hasInboundFlowReferences(method, Collections.singletonList(insn))) {
					validSequence = false;
					break;
				}

				Frame<ReValue> jframe = frames[j];
				if (isVarStore(insnOp) && insn instanceof VarInsnNode vin) {
					int index = vin.var;
					if (jframe == null || jframe.getStackSize() == 0)
						break;
					ReValue stack = jframe.getStack(jframe.getStackSize() - 1);
					if (!stack.hasKnownValue())
						break;
					sequenceVarWrites.putIfAbsent(index, stack);
				} else if (insn instanceof IincInsnNode iinc) {
					int index = iinc.var;
					if (jframe == null)
						break;
					ReValue local = jframe.getLocal(index);
					if (!local.hasKnownValue() || !(local instanceof IntValue intLocal))
						break;
					sequenceVarWrites.putIfAbsent(index, intLocal.add(iinc.incr));
				}

				int stackDiff = computeInstructionStackDifference(frames, j, insn);
				netStackChange += stackDiff;

				j--;

				if (netStackChange >= 1)
					break;
			}

			Collections.reverse(sequence);

			if (!validSequence || sequence.size() < 2
					|| shouldContinueSequence(sequence, foldedStringValue != null)
					|| !isSelfContainedAroundArrayStores(sequence))
				continue;

			if (netStackChange < (isReturn ? 0 : 1))
				continue;

			if (isReturn && isReturn(sequence.getLast())) {
				sequence.removeLast();

				if (sequence.size() < 2)
					continue;
			}

			ReValue topValue = foldedStringValue != null ? foldedStringValue : isReturn ?
					frame.getStack(frame.getStackSize() - 1) :
					nextFrame.getStack(nextFrame.getStackSize() - 1);

			if (!isReturn && !topValue.hasKnownValue() && isLabel(sequence.getLast().getNext()))
				topValue = evaluateTopFromSequence(context, method, sequence, topValue, frames, j + 1);

			AbstractInsnNode replacement = toInsn(topValue);
			if (replacement != null) {

				boolean keptSideEffects = false;
				for (AbstractInsnNode item : sequence) {
					if (item == instruction)
						continue;

					if (isSideEffectingProducer(item)) {
						Type producedType = getProducedType(item);
						if (producedType.getSort() != Type.VOID)
							instructions.insert(item, new InsnNode(producedType.getSize() == 2 ? POP2 : POP));
						keptSideEffects = true;
					} else {
						instructions.set(item, new InsnNode(NOP));
					}
				}
				if (isReturn) {
					if (keptSideEffects) {

						instructions.insertBefore(instruction, replacement);
					} else {

						AbstractInsnNode old = instructions.get(i - 1);
						instructions.set(old, replacement);
					}
				} else {
					instructions.set(instruction, replacement);

					if (!sequenceVarWrites.isEmpty()) {
						sequenceVarWrites.forEach((index, value) -> {
							AbstractInsnNode varReplacement = toInsn(value);
							VarInsnNode varStore = createVarStore(index, Objects.requireNonNull(value.type(), "Missing var type"));
							instructions.insertBefore(replacement, varReplacement);
							instructions.insertBefore(replacement, varStore);
						});

						dirty = true;
						break;
					}
				}
				dirty = true;

				if (keptSideEffects)
					break;
			} else {

				if (foldRedundantOperations(instructions, instruction, frame)) {
					dirty = true;
				} else {
					int stackSize = frame.getStackSize();
					boolean hasUnknownValue = false;
					for (int s = 0; s < stackSize; s++) {
						ReValue stack = frame.getStack(s);
						if (!stack.hasKnownValue()) {
							unknownState = i;
							hasUnknownValue = true;
							break;
						}
					}

					if (!hasUnknownValue && topValue.hasKnownValue())
						sequenceScanBoundary = i;
				}
			}
		}
		return dirty;
	}

	private boolean removeDiscardedSequences(MethodNode method,
	                                         InsnList instructions,
	                                         Frame<ReValue>[] frames,
	                                         List<AbstractInsnNode> sequence) {
		boolean dirty = false;
		for (int i = instructions.size() - 2; i >= 1; i--) {
			AbstractInsnNode instruction = instructions.get(i);
			int terminalOpcode = instruction.getOpcode();
			if ((terminalOpcode != POP && terminalOpcode != POP2) || !hasKnownDiscardedValues(frames[i], terminalOpcode))
				continue;

			boolean validSequence = true;
			int netStackChange = 0;
			sequence.clear();
			int j = i;
			while (j >= 0) {
				AbstractInsnNode insn = instructions.get(j);
				int opcode = insn.getOpcode();
				if (opcode != NOP && opcode != -1)
					sequence.add(insn);

				if (isFlowControl(insn)
						|| hasUnkeepableSideEffect(insn)
						|| isSideEffectingProducer(insn)
						|| isVarStore(opcode)
						|| insn instanceof IincInsnNode
						|| insn instanceof FieldInsnNode
						|| insn instanceof MethodInsnNode
						|| insn instanceof InvokeDynamicInsnNode
						|| isArrayStore(opcode)
						|| opcode == CHECKCAST
						|| insn.getType() == AbstractInsnNode.LABEL
						&& hasInboundFlowReferences(method, Collections.singletonList(insn))) {
					validSequence = false;
					break;
				}

				if ((opcode == POP || opcode == POP2) && !hasKnownDiscardedValues(frames[j], opcode)) {
					validSequence = false;
					break;
				}

				netStackChange += computeInstructionStackDifference(frames, j, insn);
				j--;

				if (netStackChange >= 0)
					break;
			}
			Collections.reverse(sequence);

			if (!validSequence || netStackChange != 0 || sequence.size() < 2 || shouldContinueSequence(sequence, false))
				continue;

			for (AbstractInsnNode item : sequence)
				instructions.set(item, new InsnNode(NOP));
			dirty = true;
		}
		return dirty;
	}

	private ReValue evaluateTopFromSequence(AnalysisSupport context,
	                                        MethodNode method,
	                                        List<AbstractInsnNode> sequence,
	                                        ReValue topValue,
	                                        Frame<ReValue>[] frames,
	                                        int sequenceStartIndex) {
		return topValue;
	}

	private boolean foldRedundantCheckcast(InsnList instructions,
	                                       AbstractInsnNode instruction,
	                                       Frame<ReValue> frame) {

		if (!(instruction instanceof TypeInsnNode cast) || frame.getStackSize() == 0)
			return false;

		ReValue value = frame.getStack(frame.getStackSize() - 1);
		if (!(value instanceof ObjectValue object) || !object.hasKnownValue())
			return false;

		Type valueType = object.type();
		String descriptor = cast.desc;
		Type targetType = descriptor.startsWith("[") ? Type.getType(descriptor) : Type.getObjectType(descriptor);
		if (!context.isAssignable(targetType, valueType))
			return false;

		Type staticType = getStaticOperandType(instruction);
		if (staticType == null || !context.isAssignable(targetType, staticType))
			return false;

		instructions.set(instruction, new InsnNode(NOP));
		return true;
	}

	private static boolean hasKnownDiscardedValues(Frame<ReValue> frame, int opcode) {

		if (frame == null || frame.getStackSize() == 0)
			return false;

		int stackSize = frame.getStackSize();
		ReValue top = frame.getStack(stackSize - 1);
		if (opcode == POP)
			return top.getSize() == 1 && isKnownDiscardedValue(top);
		if (opcode != POP2)
			return false;
		if (top.getSize() == 2)
			return isKnownDiscardedValue(top);
		if (top.getSize() != 1 || stackSize < 2)
			return false;
		ReValue below = frame.getStack(stackSize - 2);
		return below.getSize() == 1
				&& isKnownDiscardedValue(top)
				&& isKnownDiscardedValue(below);
	}

	private static boolean isKnownDiscardedValue(ReValue value) {
		return value.hasKnownValue() || value instanceof ObjectValue object && object.isNull();
	}

	private static Type getStaticOperandType(AbstractInsnNode consumer) {
		AbstractInsnNode producer = AsmFlowUtil.getPreviousInsn(consumer, true);
		if (producer == null)
			return null;

		return switch (producer) {
			case LdcInsnNode ldc when ldc.cst instanceof String -> Type.getObjectType("java/lang/String");
			case TypeInsnNode type when type.getOpcode() == CHECKCAST ->
					type.desc.startsWith("[") ? Type.getType(type.desc) : Type.getObjectType(type.desc);
			case FieldInsnNode field when field.getOpcode() == GETSTATIC || field.getOpcode() == GETFIELD ->
					Type.getType(field.desc);
			case MethodInsnNode method -> Type.getReturnType(method.desc);
			default -> null;
		};
	}

	@SuppressWarnings({"deprecation", "OptionalGetWithoutIsPresent"})
	private static StringValue getKnownStringConstructorValue(AbstractInsnNode instruction,
	                                                          Frame<ReValue> frame) {

		if (!(instruction instanceof MethodInsnNode constructor)
				|| instruction.getOpcode() != INVOKESPECIAL
				|| !constructor.owner.equals("java/lang/String")
				|| !constructor.name.equals("<init>"))
			return null;

		int argumentCount = Type.getArgumentCount(constructor.desc);
		int receiverIndex = frame.getStackSize() - argumentCount - 1;
		if (receiverIndex < 0
				|| !(frame.getStack(receiverIndex) instanceof ObjectValue receiver)
				|| !Types.STRING_TYPE.equals(receiver.type()) || !receiver.isNotNull())
			return null;
		int argumentsIndex = receiverIndex + 1;

		String text;
		try {
			text = switch (constructor.desc) {
				case "(Ljava/lang/String;)V" -> Values.asStringOrNull(frame.getStack(argumentsIndex));
				case "([C)V" -> {
					char[] chars = Values.getKnownCharArray(frame.getStack(argumentsIndex));
					yield chars == null ? null : new String(chars);
				}
				case "([CII)V" -> {
					char[] chars = Values.getKnownCharArray(frame.getStack(argumentsIndex));
					IntValue offset = getKnownIntValue(frame, argumentsIndex + 1);
					IntValue count = getKnownIntValue(frame, argumentsIndex + 2);
					yield chars == null || offset == null || count == null ? null :
							new String(chars, offset.value().getAsInt(), count.value().getAsInt());
				}
				case "([III)V" -> {
					int[] codePoints = Values.getKnownIntArray(frame.getStack(argumentsIndex));
					IntValue offset = getKnownIntValue(frame, argumentsIndex + 1);
					IntValue count = getKnownIntValue(frame, argumentsIndex + 2);
					yield codePoints == null || offset == null || count == null ? null :
							new String(codePoints, offset.value().getAsInt(), count.value().getAsInt());
				}
				case "([BI)V" -> {
					byte[] bytes = Values.getKnownByteArray(frame.getStack(argumentsIndex));
					IntValue highByte = getKnownIntValue(frame, argumentsIndex + 1);
					yield bytes == null || highByte == null ? null :
							new String(bytes, highByte.value().getAsInt());
				}
				case "([BIII)V" -> {
					byte[] bytes = Values.getKnownByteArray(frame.getStack(argumentsIndex));
					IntValue highByte = getKnownIntValue(frame, argumentsIndex + 1);
					IntValue offset = getKnownIntValue(frame, argumentsIndex + 2);
					IntValue count = getKnownIntValue(frame, argumentsIndex + 3);
					yield bytes == null || highByte == null || offset == null || count == null ? null :
							new String(bytes, highByte.value().getAsInt(), offset.value().getAsInt(), count.value().getAsInt());
				}
				case "([BIILjava/lang/String;)V" -> {
					byte[] bytes = Values.getKnownByteArray(frame.getStack(argumentsIndex));
					IntValue offset = getKnownIntValue(frame, argumentsIndex + 1);
					IntValue length = getKnownIntValue(frame, argumentsIndex + 2);
					String charsetName = Values.asStringOrNull(frame.getStack(argumentsIndex + 3));
					yield bytes == null || offset == null || length == null || charsetName == null ? null :
							new String(bytes, offset.value().getAsInt(), length.value().getAsInt(), charsetName);
				}
				case "([BIILjava/nio/charset/Charset;)V" -> {
					byte[] bytes = Values.getKnownByteArray(frame.getStack(argumentsIndex));
					IntValue offset = getKnownIntValue(frame, argumentsIndex + 1);
					IntValue length = getKnownIntValue(frame, argumentsIndex + 2);
					Charset charset = getKnownCharset(frame.getStack(argumentsIndex + 3), instruction);
					yield bytes == null || offset == null || length == null || charset == null ? null :
							new String(bytes, offset.value().getAsInt(), length.value().getAsInt(), charset);
				}
				case "([BLjava/lang/String;)V" -> {
					byte[] bytes = Values.getKnownByteArray(frame.getStack(argumentsIndex));
					String charsetName = Values.asStringOrNull(frame.getStack(argumentsIndex + 1));
					yield bytes == null || charsetName == null ? null : new String(bytes, charsetName);
				}
				case "([BLjava/nio/charset/Charset;)V" -> {
					byte[] bytes = Values.getKnownByteArray(frame.getStack(argumentsIndex));
					Charset charset = getKnownCharset(frame.getStack(argumentsIndex + 1), instruction);
					yield bytes == null || charset == null ? null : new String(bytes, charset);
				}
				case "([BII)V" -> {
					byte[] bytes = Values.getKnownByteArray(frame.getStack(argumentsIndex));
					IntValue offset = getKnownIntValue(frame, argumentsIndex + 1);
					IntValue length = getKnownIntValue(frame, argumentsIndex + 2);
					yield bytes == null || offset == null || length == null ? null :
							new String(bytes, offset.value().getAsInt(), length.value().getAsInt());
				}
				case "([B)V" -> {
					byte[] bytes = Values.getKnownByteArray(frame.getStack(argumentsIndex));
					yield bytes == null ? null : new String(bytes);
				}
				default -> null;
			};
		} catch (Exception ex) {

			return null;
		}

		return text == null ? null : ObjectValue.string(text);
	}

	private static IntValue getKnownIntValue(Frame<ReValue> frame, int index) {
		if (frame.getStack(index) instanceof IntValue intValue && intValue.hasKnownValue())
			return intValue;
		return null;
	}

	private static Charset getKnownCharset(ReValue value, AbstractInsnNode constructor) {
		if (!(value instanceof ObjectValue object) || !Types.CHARSET_TYPE.equals(object.type()))
			return null;

		AbstractInsnNode producer = getPreviousInsn(constructor, true);
		if (producer instanceof FieldInsnNode field
				&& field.getOpcode() == GETSTATIC
				&& field.owner.equals("java/nio/charset/StandardCharsets")
				&& Types.CHARSET_TYPE.equals(Type.getType(field.desc)))
			return switch (field.name) {
				case "ISO_8859_1" -> StandardCharsets.ISO_8859_1;
				case "US_ASCII" -> StandardCharsets.US_ASCII;
				case "UTF_8" -> StandardCharsets.UTF_8;
				case "UTF_16" -> StandardCharsets.UTF_16;
				case "UTF_16BE" -> StandardCharsets.UTF_16BE;
				case "UTF_16LE" -> StandardCharsets.UTF_16LE;
				case "UTF_32" -> StandardCharsets.UTF_32;
				case "UTF_32BE" -> StandardCharsets.UTF_32BE;
				case "UTF_32LE" -> StandardCharsets.UTF_32LE;
				default -> null;
			};
		return null;
	}

	private static boolean isStringToStringCall(AbstractInsnNode instruction) {
		return instruction instanceof MethodInsnNode method
				&& method.getOpcode() == INVOKEVIRTUAL
				&& method.owner.equals("java/lang/String")
				&& method.name.equals("toString")
				&& method.desc.equals("()Ljava/lang/String;");
	}

	private static boolean foldRedundantStringToString(InsnList instructions,
	                                                   AbstractInsnNode instruction,
	                                                   Frame<ReValue> frame) {
		if (!isStringToStringCall(instruction) || frame.getStackSize() == 0)
			return false;

		ReValue receiver = frame.getStack(frame.getStackSize() - 1);
		boolean receiverIsNonNull = receiver instanceof ObjectValue object
				&& Types.STRING_TYPE.equals(object.type())
				&& object.isNotNull();
		AbstractInsnNode producer = getPreviousInsn(instruction, true);
		boolean producerReturnsNonNullString = producer instanceof MethodInsnNode method
				&& method.getOpcode() == INVOKEVIRTUAL
				&& method.name.equals("toString")
				&& method.desc.equals("()Ljava/lang/String;")
				&& (method.owner.equals("java/lang/String")
				|| method.owner.equals("java/lang/StringBuilder"));
		if (!receiverIsNonNull && !producerReturnsNonNullString)
			return false;

		instructions.set(instruction, new InsnNode(NOP));
		return true;
	}

	private static boolean foldRedundantOperations(InsnList instructions, AbstractInsnNode instruction, Frame<ReValue> frame) {
		if (foldRedundantStringToString(instructions, instruction, frame))
			return true;

		if (frame.getStackSize() < 2)
			return false;

		ReValue top = frame.getStack(frame.getStackSize() - 1);
		ReValue topM1 = frame.getStack(frame.getStackSize() - 2);
		int opcode = instruction.getOpcode();
		int targetValue = switch (opcode) {
			case IAND, LAND -> -1;
			case IMUL, FMUL, DMUL, LMUL,
			     IDIV, FDIV, DDIV, LDIV -> 1;
			case IADD, FADD, DADD, LADD,
			     IOR, LOR,
			     IXOR, LXOR,
			     ISHL, ISHR, IUSHR, LSHL, LSHR, LUSHR -> 0;
			default -> 25565;
		};

		if (targetValue == 25565)
			return false;

		boolean rightOperandOnlyIdentity = switch (opcode) {
			case IDIV, FDIV, DDIV, LDIV,
			     ISHL, ISHR, IUSHR, LSHL, LSHR, LUSHR -> true;
			default -> false;
		};

		if (ReValue.isPrimitiveEqualTo(top, targetValue)) {

			BinaryOperationArguments arguments = getBinaryOperationArguments(instruction.getPrevious(), opcode);
			if (arguments == null)
				return false;

			instructions.set(instruction, new InsnNode(NOP));
			instructions.set(arguments.argument2().insn(), new InsnNode(NOP));
			for (AbstractInsnNode intermediate : arguments.argument2().intermediates())
				instructions.set(intermediate, new InsnNode(NOP));
			return true;
		} else if (!rightOperandOnlyIdentity && ReValue.isPrimitiveEqualTo(topM1, targetValue)) {

			BinaryOperationArguments arguments = getBinaryOperationArguments(instruction.getPrevious(), opcode);
			if (arguments == null)
				return false;

			instructions.set(instruction, new InsnNode(NOP));
			instructions.set(arguments.argument1().insn(), new InsnNode(NOP));
			for (AbstractInsnNode intermediate : arguments.argument1().intermediates())
				instructions.set(intermediate, new InsnNode(NOP));
			return true;
		}
		return false;
	}

	private static int computeInstructionStackDifference(Frame<ReValue>[] frames, int i, AbstractInsnNode insn) {

		Frame<ReValue> thisFrame = frames[i];
		Frame<ReValue> nextFrame = frames[i + 1];
		if (thisFrame != null && nextFrame != null) {
			int jsize = thisFrame.getStackSize();
			int jsizeNext = nextFrame.getStackSize();
			return jsizeNext - jsize;
		}

		int consumed = getSizeConsumed(insn);
		int produced = getSizeProduced(insn);
		return produced - consumed;
	}

	private static boolean shouldContinueSequence(List<AbstractInsnNode> sequence, boolean stringConstructorResult) {
		int stackDiff = 0;
		int consumed = 0;
		int produced = 0;
		int netStackChange = 0;
		for (AbstractInsnNode seq : sequence) {

			int op = seq.getOpcode();
			if ((op == DUP && netStackChange < 1)
					|| (op == DUP_X1 && netStackChange < 2)
					|| (op == DUP_X2 && netStackChange < 3)
					|| (op == DUP2 && netStackChange < 2)
					|| (op == DUP2_X1 && netStackChange < 3)
					|| (op == DUP2_X2 && netStackChange < 4)
					|| (op == SWAP && netStackChange < 2)
					|| (op == POP && netStackChange < 1)
					|| (op == POP2 && netStackChange < 2)) {
				return true;
			}

			consumed = getSizeConsumed(seq);
			produced = getSizeProduced(seq);

			if (consumed > netStackChange)
				return true;

			stackDiff = produced - consumed;
			netStackChange += stackDiff;
		}
		if (stringConstructorResult)
			produced = 1;
		return netStackChange > consumed || netStackChange != produced;
	}

	public static boolean isSupportedValueProducer(AbstractInsnNode insn) {

		if (getSizeConsumed(insn) > 0)
			return false;

		int op = insn.getOpcode();
		if (isConstValue(op))
			return true;
		if (op >= ILOAD && op <= ALOAD)
			return true;
		if (op == GETSTATIC)
			return insn instanceof FieldInsnNode fin && STATIC_FIELD_LOOKUP.hasLookup(fin);
		return op == INVOKESTATIC
				&& insn instanceof MethodInsnNode min
				&& min.desc.startsWith("()")
				&& !min.desc.endsWith(")V")
				&& STATIC_METHOD_LOOKUP.hasLookup(min);
	}

	private static boolean isSideEffectingProducer(AbstractInsnNode insn) {
		return switch (insn) {
			case MethodInsnNode min when min.getOpcode() == INVOKESTATIC ->
					min.desc.startsWith("()") && !STATIC_METHOD_LOOKUP.hasLookup(min);
			case FieldInsnNode fin when fin.getOpcode() == GETSTATIC -> !STATIC_FIELD_LOOKUP.hasLookup(fin);
			default -> false;
		};
	}

	private static boolean isSelfContainedAroundArrayStores(List<AbstractInsnNode> sequence) {
		if (sequence.stream().noneMatch(insn -> isArrayStore(insn.getOpcode())))
			return true;

		Set<Integer> assignedVariables = new HashSet<>();
		for (AbstractInsnNode insn : sequence) {
			switch (insn) {
				case VarInsnNode varInsn when varInsn.getOpcode() == ASTORE -> assignedVariables.add(varInsn.var);
				case VarInsnNode varInsn when varInsn.getOpcode() == ALOAD -> {
					if (!assignedVariables.contains(varInsn.var))
						return false;
				}
				case FieldInsnNode fieldInsn when fieldInsn.getOpcode() == GETSTATIC || fieldInsn.getOpcode() == GETFIELD -> {
					Type fieldType = Type.getType(fieldInsn.desc);
					if (Types.isReference(fieldType) && !Types.CHARSET_TYPE.equals(fieldType))
						return false;
				}
				case MethodInsnNode methodInsn when isSideEffectingProducer(methodInsn) -> {
					if (Types.isReference(Type.getReturnType(methodInsn.desc)))
						return false;
				}
				default -> {

				}
			}
		}
		return true;
	}

	private static boolean hasUnkeepableSideEffect(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (op == PUTSTATIC || op == PUTFIELD || op == MONITORENTER || op == MONITOREXIT)
			return true;
		return switch (insn) {
			case InvokeDynamicInsnNode ignored -> true;
			case MethodInsnNode min -> !isSideEffectingProducer(min) && !(min.getOpcode() == INVOKESTATIC ?
					STATIC_METHOD_LOOKUP.hasLookup(min) : VIRTUAL_METHOD_LOOKUP.hasLookup(min));
			default -> false;
		};
	}

	private static Type getProducedType(AbstractInsnNode insn) {
		if (insn instanceof MethodInsnNode min)
			return Type.getReturnType(min.desc);
		if (insn instanceof FieldInsnNode fin)
			return Type.getType(fin.desc);
		throw new IllegalArgumentException("Instruction is not a side effecting producer: " + insn.getClass().getSimpleName() + " " + insn.getOpcode());
	}

	@SuppressWarnings("OptionalGetWithoutIsPresent")
	public static AbstractInsnNode toInsn(ReValue value) {
		if (value == null)
			return null;

		if (value == ObjectValue.VAL_OBJECT_NULL || value instanceof ObjectValue objectValue && objectValue.isNull())
			return new InsnNode(Opcodes.ACONST_NULL);

		if (!value.hasKnownValue())
			return null;

		return switch (value) {
			case IntValue intValue -> intToInsn(intValue.value().getAsInt());
			case FloatValue floatValue -> floatToInsn((float) floatValue.value().getAsDouble());
			case DoubleValue doubleValue -> doubleToInsn(doubleValue.value().getAsDouble());
			case LongValue longValue -> longToInsn(longValue.value().getAsLong());
			case StringValue stringValue -> new LdcInsnNode(stringValue.getText().get());
			default -> null;
		};
	}

	private static int getSlotsOccupied(Frame<ReValue> frame) {
		int valueCount = frame.getStackSize();
		int slots = 0;
		for (int i = 0; i < valueCount; i++) {
			ReValue value = frame.getStack(i);
			slots += value.getSize();
		}
		return slots;
	}

	public static BinaryOperationArguments getBinaryOperationArguments(AbstractInsnNode insnBeforeOp, int binOperationOpcode) {

		Argument argument2 = collectArgument(insnBeforeOp);
		if (argument2 == null)
			return null;

		Argument argument1;
		if (argument2.providesBinaryOpValuesFor(binOperationOpcode)) {

			argument1 = argument2;
		} else {
			argument1 = collectArgument(argument2.insn().getPrevious());

			if (argument1 == null)
				return null;

			if (argument1.providesBinaryOpValuesFor(binOperationOpcode))
				return null;
		}

		int swapCount = (int) argument2.intermediates().stream()
				.filter(i -> i.getOpcode() == SWAP)
				.count();
		if (swapCount % 2 == 1) {
			Argument temp = argument1;
			argument1 = argument2;
			argument2 = temp;
		}

		List<AbstractInsnNode> combinedIntermediates = new ArrayList<>(argument1.getCombinedIntermediates(argument2));
		if (!canConsumeAccumulatedStackConsumption(argument1, argument2, combinedIntermediates))
			return null;
		return new BinaryOperationArguments(argument2, argument1, combinedIntermediates);
	}

	public static Argument collectArgument(AbstractInsnNode insnBeforeOp) {
		if (insnBeforeOp == null)
			return null;
		List<AbstractInsnNode> intermediates = null;
		int intermediateStackConsumption = 0;
		while (insnBeforeOp != null) {
			int argumentOp = insnBeforeOp.getOpcode();
			if (argumentOp == NOP) {
				insnBeforeOp = insnBeforeOp.getPrevious();
			} else if (argumentOp == SWAP || argumentOp == POP || argumentOp == POP2) {

				if (intermediates == null)
					intermediates = new ArrayList<>();
				intermediates.add(insnBeforeOp);
				intermediateStackConsumption += getSizeConsumed(insnBeforeOp);
				insnBeforeOp = insnBeforeOp.getPrevious();
			} else {
				break;
			}
		}
		if (insnBeforeOp == null || !isSupportedValueProducer(insnBeforeOp))
			return null;
		return new Argument(insnBeforeOp, Objects.requireNonNullElse(intermediates, Collections.emptyList()), intermediateStackConsumption);
	}

	private static boolean canConsumeAccumulatedStackConsumption(Argument argument1, Argument argument2,
	                                                             List<AbstractInsnNode> intermediates) {

		AbstractInsnNode insn = argument1.insn();

		int intermediateStackConsumption = argument1.getCombinedStackConsumption(argument2);
		return canConsumeAccumulatedStackConsumption(intermediateStackConsumption, intermediates, insn);
	}

	private static boolean canConsumeAccumulatedStackConsumption(int intermediateStackConsumption,
	                                                             List<AbstractInsnNode> intermediates,
	                                                             AbstractInsnNode start) {

		AbstractInsnNode insn = start;
		while (intermediateStackConsumption > 0) {
			insn = insn.getPrevious();
			if (insn == null)
				break;
			if (insn.getOpcode() == NOP)
				continue;
			if (isSupportedValueProducer(insn)) {
				intermediates.add(insn);
				intermediateStackConsumption -= getSizeProduced(insn);
			} else {

				return false;
			}
		}

		return intermediateStackConsumption == 0;
	}

	static {
		Arrays.fill(ARG_1_SIZE, -1);
		Arrays.fill(ARG_2_SIZE, -1);

		ARG_1_SIZE[IADD] = 1;
		ARG_1_SIZE[FADD] = 1;
		ARG_1_SIZE[ISUB] = 1;
		ARG_1_SIZE[FSUB] = 1;
		ARG_1_SIZE[IMUL] = 1;
		ARG_1_SIZE[FMUL] = 1;
		ARG_1_SIZE[IDIV] = 1;
		ARG_1_SIZE[FDIV] = 1;
		ARG_1_SIZE[IREM] = 1;
		ARG_1_SIZE[FREM] = 1;
		ARG_1_SIZE[ISHL] = 1;
		ARG_1_SIZE[ISHR] = 1;
		ARG_1_SIZE[IUSHR] = 1;
		ARG_1_SIZE[IAND] = 1;
		ARG_1_SIZE[IXOR] = 1;
		ARG_1_SIZE[IOR] = 1;
		ARG_1_SIZE[DREM] = 2;
		ARG_1_SIZE[DDIV] = 2;
		ARG_1_SIZE[DMUL] = 2;
		ARG_1_SIZE[DSUB] = 2;
		ARG_1_SIZE[DADD] = 2;
		ARG_1_SIZE[LUSHR] = 2;
		ARG_1_SIZE[LSHR] = 2;
		ARG_1_SIZE[LSHL] = 2;
		ARG_1_SIZE[LREM] = 2;
		ARG_1_SIZE[LDIV] = 2;
		ARG_1_SIZE[LMUL] = 2;
		ARG_1_SIZE[LSUB] = 2;
		ARG_1_SIZE[LADD] = 2;
		ARG_1_SIZE[LAND] = 2;
		ARG_1_SIZE[LOR] = 2;
		ARG_1_SIZE[LXOR] = 2;
		ARG_1_SIZE[FCMPL] = 1;
		ARG_1_SIZE[FCMPG] = 1;
		ARG_1_SIZE[LCMP] = 2;
		ARG_1_SIZE[DCMPL] = 2;
		ARG_1_SIZE[DCMPG] = 2;

		System.arraycopy(ARG_1_SIZE, 0, ARG_2_SIZE, 0, ARG_1_SIZE.length);
		ARG_2_SIZE[LUSHR] = 1;
		ARG_2_SIZE[LSHR] = 1;
		ARG_2_SIZE[LSHL] = 1;

		ARG_1_SIZE[DUP] = 1;
		ARG_1_SIZE[DUP_X1] = 1;
		ARG_1_SIZE[DUP_X2] = 1;
		ARG_1_SIZE[DUP2] = 1;
		ARG_2_SIZE[DUP2] = 1;
		ARG_1_SIZE[DUP2_X1] = 1;
		ARG_2_SIZE[DUP2_X1] = 1;
		ARG_1_SIZE[DUP2_X2] = 1;
		ARG_2_SIZE[DUP2_X2] = 1;
		ARG_1_SIZE[POP] = 1;
		ARG_1_SIZE[POP2] = 1;
		ARG_2_SIZE[POP2] = 1;

	}

	public record BinaryOperationArguments(Argument argument2, Argument argument1,
	                                       List<AbstractInsnNode> combinedIntermediates) {

		public void replaceBinOp(InsnList instructions) {

			argument2.replaceInsn(instructions);

			if (!argument1.sameAs(argument2))
				argument1.replaceInsn(instructions);

			for (AbstractInsnNode intermediate : combinedIntermediates)
				if (instructions.contains(intermediate))
					instructions.set(intermediate, new InsnNode(NOP));
		}
	}

	public record Argument(AbstractInsnNode insn,
	                       List<AbstractInsnNode> intermediates,
	                       int intermediateStackConsumption) {

		public boolean hasIntermediates() {
			return !intermediates.isEmpty();
		}

		public boolean sameAs(Argument other) {
			return insn == other.insn;
		}

		public int getCombinedStackConsumption(Argument other) {
			return sameAs(other) ?
					intermediateStackConsumption :
					intermediateStackConsumption + other.intermediateStackConsumption;
		}

		public List<AbstractInsnNode> getCombinedIntermediates(Argument other) {
			if (!hasIntermediates() && !other.hasIntermediates())
				return Collections.emptyList();
			if (sameAs(other) || !other.hasIntermediates())
				return intermediates;
			return combineLists(intermediates, other.intermediates);
		}

		@Override
		public String toString() {
			String string = AsmFlowUtil.describeInsn(insn);
			if (intermediates.isEmpty())
				return string;
			return string + "\n - " + intermediates.stream().map(AsmFlowUtil::describeInsn).collect(Collectors.joining("\n - "));
		}

		public boolean providesBinaryOpValuesFor(int opcode) {

			int arg1Size = ARG_1_SIZE[opcode];
			int arg2Size = ARG_2_SIZE[opcode];
			if (arg1Size < 0 || arg2Size < 0)
				throw new IllegalStateException("Missing arg sizes for op: " + opcode);

			int totalArgSize = arg1Size + arg2Size;
			if (getSizeProduced(insn) == totalArgSize)
				return true;

			return insn.getOpcode() == DUP2
					&& arg1Size == 1
					&& arg2Size == 1;
		}

		public boolean replaceInsn(InsnList instructions) {
			instructions.set(insn, new InsnNode(NOP));
			return true;
		}
	}

	private static <T> List<T> combineLists(List<T> first, List<T> second) {
		List<T> combined = new java.util.ArrayList<>(first.size() + second.size());
		combined.addAll(first);
		combined.addAll(second);
		return combined;
	}
}

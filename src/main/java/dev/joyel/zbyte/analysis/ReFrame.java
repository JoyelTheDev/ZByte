package dev.joyel.zbyte.analysis;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import dev.joyel.zbyte.analysis.value.ArrayValue;
import dev.joyel.zbyte.analysis.value.IntValue;
import dev.joyel.zbyte.analysis.value.ObjectValue;
import dev.joyel.zbyte.analysis.value.ReValue;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public class ReFrame extends Frame<ReValue> {
	private final ReAnalyzer analyzer;
	private final ReValue[] localsSnapshot;
	private final ReValue[] stackSnapshot;
	private int stackSnapshotSize;
	private int localsSnapshotSize;
	private Branching branchState;
	private boolean branchStateChanged;
	private boolean template;

	public ReFrame(ReAnalyzer analyzer, int numLocals, int maxStack) {
		super(numLocals, maxStack);
		this.analyzer = analyzer;
		localsSnapshot = new ReValue[numLocals];
		stackSnapshot = new ReValue[maxStack];
	}

	public ReFrame(ReAnalyzer analyzer, Frame<? extends ReValue> frame) {
		super(frame);
		this.analyzer = analyzer;
		localsSnapshot = new ReValue[frame.getLocals()];
		stackSnapshot = new ReValue[frame.getMaxStackSize()];
	}

	public Optional<ReValue> getStackTop() {
		int stackSize = getStackSize();
		if (stackSize == 0)
			return Optional.empty();
		return Optional.of(getStack(stackSize - 1));
	}

	@Override
	public void execute(AbstractInsnNode insn, Interpreter<ReValue> interpreter) throws AnalyzerException {

		if (analyzer != null) {

			analyzer.notifyCurrentInsn(insn);

			addBranchingBehavior(Branching.TAKEN);

			updateLocalAndStackSnapshots();
		}

		switch (insn.getOpcode()) {
			case Opcodes.IASTORE:
			case Opcodes.LASTORE:
			case Opcodes.FASTORE:
			case Opcodes.DASTORE:
			case Opcodes.AASTORE:
			case Opcodes.BASTORE:
			case Opcodes.CASTORE:
			case Opcodes.SASTORE:

				ReValue value = pop();
				ReValue index = pop();
				ReValue array = pop();
				ReValue updatedArray = interpreter.ternaryOperation(insn, array, index, value);
				if (updatedArray != array)
					replaceValue(array, updatedArray);
				break;
			default:
				super.execute(insn, interpreter);
		}
	}

	@Override
	public void initJumpTarget(int opcode, LabelNode target) {

		if (analyzer == null)
			return;

		switch (opcode) {
			case Opcodes.IFEQ -> notifyUnary(target, top -> top.isEqualTo(0));
			case Opcodes.IFNE -> notifyUnary(target, top -> top.isNotEqualTo(0));
			case Opcodes.IFLT -> notifyUnary(target, top -> top.isLessThan(0));
			case Opcodes.IFGE -> notifyUnary(target, top -> top.isGreaterThanOrEqual(0));
			case Opcodes.IFGT -> notifyUnary(target, top -> top.isGreaterThan(0));
			case Opcodes.IFLE -> notifyUnary(target, top -> top.isLessThanOrEqual(0));
			case Opcodes.IFNULL -> notifyUnaryNullness(target, top ->
					top.nullness() == Nullness.UNKNOWN ?
							Branching.UNKNOWN :
							top.isNull() ?
									Branching.TAKEN : Branching.NOT_TAKEN);
			case Opcodes.IFNONNULL -> notifyUnaryNullness(target, top ->
					top.nullness() == Nullness.UNKNOWN ?
							Branching.UNKNOWN :
							top.isNotNull() ?
									Branching.TAKEN : Branching.NOT_TAKEN);
			case Opcodes.IF_ICMPEQ -> notifyBinary(target, IntValue::isEqualTo);
			case Opcodes.IF_ICMPNE -> notifyBinary(target, IntValue::isNotEqualTo);
			case Opcodes.IF_ICMPLT -> notifyBinary(target, IntValue::isLessThan);
			case Opcodes.IF_ICMPGE -> notifyBinary(target, IntValue::isGreaterThanOrEqual);
			case Opcodes.IF_ICMPGT -> notifyBinary(target, IntValue::isGreaterThan);
			case Opcodes.IF_ICMPLE -> notifyBinary(target, IntValue::isLessThanOrEqual);
			case Opcodes.IF_ACMPEQ -> notifyBinaryRefs(target, (l, r) ->
					l.nullness() == Nullness.UNKNOWN || r.nullness() == Nullness.UNKNOWN ?
							Branching.UNKNOWN :
							l == r ?
									Branching.TAKEN :
									Branching.UNKNOWN);
			case Opcodes.IF_ACMPNE -> notifyBinaryRefs(target, (l, r) ->
					l.nullness() == Nullness.UNKNOWN || r.nullness() == Nullness.UNKNOWN ?
							Branching.UNKNOWN :
							l == r ?
									Branching.NOT_TAKEN :
									Branching.UNKNOWN);
			default -> {}
		}
	}

	private void notifyUnary(LabelNode target, Predicate<IntValue> predicate) {
		ReValue top = stackSnapshot[stackSnapshotSize - 1];
		if (top.hasKnownValue() && top instanceof IntValue intTop) {
			Branching branching = predicate.test(intTop) ? Branching.TAKEN : Branching.NOT_TAKEN;
			analyzer.notifyJumpVisited(target, branching);
		} else {
			analyzer.notifyJumpVisited(target, Branching.UNKNOWN);
		}
	}

	private void notifyUnaryNullness(LabelNode target, UnBranchingComputer<ObjectValue> predicate) {
		ReValue top = stackSnapshot[stackSnapshotSize - 1];
		if (top instanceof ObjectValue objTop) {
			Branching branching = predicate.compute(objTop);
			analyzer.notifyJumpVisited(target, branching);
		} else {
			analyzer.notifyJumpVisited(target, Branching.UNKNOWN);
		}
	}

	private void notifyBinary(LabelNode target, BiPredicate<IntValue, IntValue> predicate) {
		ReValue topL = stackSnapshot[stackSnapshotSize - 2];
		ReValue topR = stackSnapshot[stackSnapshotSize - 1];
		if (topL.hasKnownValue() && topL instanceof IntValue intTopLeft &&
				topR.hasKnownValue() && topR instanceof IntValue intTopRight) {
			Branching branching = predicate.test(intTopLeft, intTopRight) ? Branching.TAKEN : Branching.NOT_TAKEN;
			analyzer.notifyJumpVisited(target, branching);
		} else {
			analyzer.notifyJumpVisited(target, Branching.UNKNOWN);
		}
	}

	private void notifyBinaryRefs(LabelNode target, BiBranchingComputer<ObjectValue> predicate) {
		ReValue topL = stackSnapshot[stackSnapshotSize - 2];
		ReValue topR = stackSnapshot[stackSnapshotSize - 1];
		if (topL.hasKnownValue() && topL instanceof ObjectValue objTopLeft &&
				topR.hasKnownValue() && topR instanceof ObjectValue objTopRight) {
			Branching branching = predicate.compute(objTopLeft, objTopRight);
			analyzer.notifyJumpVisited(target, branching);
		} else {
			analyzer.notifyJumpVisited(target, Branching.UNKNOWN);
		}
	}

	@Override
	public boolean merge(Frame<? extends ReValue> frame, Interpreter<ReValue> interpreter) throws AnalyzerException {
		if (analyzer != null && template) {

			init(frame);
			template = false;
			branchStateChanged = false;

			return !isNeverBranchedTo();
		}

		boolean valuesChanged = super.merge(frame, interpreter);
		boolean branchingChanged = branchStateChanged;
		branchStateChanged = false;
		return valuesChanged || branchingChanged;
	}

	private void updateLocalAndStackSnapshots() {
		int stackSnapshotSize = getStackSize();
		int localsSnapshotSize = getLocals();

		this.stackSnapshotSize = stackSnapshotSize;
		this.localsSnapshotSize = localsSnapshotSize;

		Arrays.fill(stackSnapshot, stackSnapshotSize, stackSnapshot.length, null);
		for (int i = 0; i < stackSnapshotSize; i++)
			stackSnapshot[i] = getStack(i);
		for (int i = 0; i < localsSnapshotSize; i++)
			localsSnapshot[i] = getLocal(i);
	}

	public void replaceValue(ReValue existing, ReValue replacement) {
		for (int i = 0; i < getStackSize(); i++) {
			ReValue stack = getStack(i);
			if (stack == existing) {
				setStack(i, replacement);
			} else if (stack instanceof ArrayValue stackArray
					&& (existing instanceof ArrayValue ? stackArray.containsTrackedSubArray() : true)
					&& stackArray.canStore(replacement, true)) {
				ArrayValue updatedStackArray = stackArray.updatedCopyIfContained(existing, replacement);
				setStack(i, updatedStackArray);
			}
		}
		for (int i = 0; i < getLocals(); i++) {
			ReValue local = getLocal(i);
			if (local == existing) {
				setLocal(i, replacement);
			} else if (local instanceof ArrayValue stackArray
					&& (existing instanceof ArrayValue ? stackArray.containsTrackedSubArray() : true)
					&& stackArray.canStore(replacement, true)) {
				ArrayValue updatedStackArray = stackArray.updatedCopyIfContained(existing, replacement);
				setLocal(i, updatedStackArray);
			}
		}
	}

	public boolean isNeverBranchedTo() {
		return branchState == Branching.NOT_TAKEN;
	}

	protected void addBranchingBehavior(Branching branching) {
		Branching previousState = branchState;
		if (branchState == null)
			branchState = branching;
		else if (branching == Branching.UNKNOWN)
			branchState = Branching.UNKNOWN;
		else if (branchState == Branching.TAKEN && branching == Branching.NOT_TAKEN)
			branchState = Branching.UNKNOWN;
		else if (branchState == Branching.NOT_TAKEN && branching == Branching.TAKEN)
			branchState = Branching.UNKNOWN;
		if (previousState != branchState)
			branchStateChanged = true;
	}

	public boolean isTemplate() {
		return template;
	}

	protected void markTemplate() {
		template = true;
	}

	@Override
	public String toString() {
		if (template) return "<template>";
		return super.toString();
	}

	private interface UnBranchingComputer<T extends ReValue> {
		Branching compute(T value);
	}

	private interface BiBranchingComputer<T extends ReValue> {
		Branching compute(T valueLeft, T valueRight);
	}
}

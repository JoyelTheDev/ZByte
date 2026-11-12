package dev.joyel.zbyte.analysis;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import dev.joyel.zbyte.analysis.value.ReValue;

import java.util.function.Consumer;

public class ReAnalyzer extends Analyzer<ReValue> {
	private final ReInterpreter interpreter;
	private MethodNode targetMethod;
	private AbstractInsnNode currentInsn;
	private boolean pruneOutputTemplateFrames = true;
	private boolean pruneOutputDeadCodeFrames = true;

	public ReAnalyzer(ReInterpreter interpreter) {
		super(interpreter);
		this.interpreter = interpreter;
	}

	public ReInterpreter getInterpreter() {
		return interpreter;
	}

	public void setPruneOutputTemplateFrames(boolean pruneOutputTemplateFrames) {
		this.pruneOutputTemplateFrames = pruneOutputTemplateFrames;
	}

	public void setPruneOutputDeadCodeFrames(boolean pruneOutputDeadCodeFrames) {
		this.pruneOutputDeadCodeFrames = pruneOutputDeadCodeFrames;
	}

	@Override
	protected ReFrame newFrame(int numLocals, int numStack) {
		return new ReFrame(this, numLocals, numStack);
	}

	@Override
	protected ReFrame newFrame(Frame<? extends ReValue> frame) {
		return new ReFrame(this, frame);
	}

	@Override
	protected void init(String owner, MethodNode method) throws AnalyzerException {
		targetMethod = method;
		fillJumpTargetTemplateFrames();
	}

	@Override
	public Frame<ReValue>[] analyze(String owner, MethodNode method) throws AnalyzerException {

		Frame<ReValue>[] frames = super.analyze(owner, method);

		int frameCount = frames.length;
		for (int i = 0; i < frameCount; i++) {
			ReFrame frame = (ReFrame) frames[i];
			if (frame == null)
				continue;
			if (pruneOutputTemplateFrames && frame.isTemplate())
				frames[i] = null;
			if (pruneOutputDeadCodeFrames && frame.isNeverBranchedTo())
				frames[i] = null;
		}

		return frames;
	}

	protected void fillJumpTargetTemplateFrames() {
		InsnList instructions = targetMethod.instructions;
		Frame<ReValue>[] frames = getFrames();
		Consumer<AbstractInsnNode> frameFill = insn -> {
			if (insn == null) return;
			int index = instructions.indexOf(insn);
			if (index > 0 && index < frames.length) {
				ReFrame frame = newFrame(targetMethod.maxLocals, targetMethod.maxStack);
				frame.markTemplate();
				frames[index] = frame;
			}
		};
		Consumer<AbstractInsnNode> targetFill = insn -> {
			if (insn != null && !canBeReachedByFallthrough(insn))
				frameFill.accept(insn);
		};
		for (AbstractInsnNode instruction : instructions) {
			if (instruction instanceof JumpInsnNode jin) {
				targetFill.accept(jin.label);
				int opcode = jin.getOpcode();
				if (opcode != GOTO && opcode != JSR)
					frameFill.accept(jin.getNext());
			} else if (instruction instanceof TableSwitchInsnNode tswitch) {
				targetFill.accept(tswitch.dflt);
				tswitch.labels.forEach(targetFill);
			} else if (instruction instanceof LookupSwitchInsnNode lswitch) {
				targetFill.accept(lswitch.dflt);
				lswitch.labels.forEach(targetFill);
			}
		}
	}

	private static boolean canBeReachedByFallthrough(AbstractInsnNode target) {
		AbstractInsnNode previous = target.getPrevious();
		while (previous != null && previous.getOpcode() < 0)
			previous = previous.getPrevious();
		if (previous == null)
			return true;
		switch (previous.getOpcode()) {
			case GOTO:
			case ATHROW:
			case IRETURN:
			case LRETURN:
			case FRETURN:
			case DRETURN:
			case ARETURN:
			case RETURN:
			case TABLESWITCH:
			case LOOKUPSWITCH:
				return false;
			default:
				return true;
		}
	}

	protected void notifyCurrentInsn(AbstractInsnNode insn) {
		currentInsn = insn;
	}

	protected void notifyJumpVisited(LabelNode target, Branching branching) {
		if (target == null) {

			AbstractInsnNode next = currentInsn.getNext();
			if (next == null)
				return;
			int targetIndex = targetMethod.instructions.indexOf(next);
			if (targetIndex < 0)
				return;

			ReFrame targetFrame = (ReFrame) getFrames()[targetIndex];
			if (targetFrame != null)
				targetFrame.addBranchingBehavior(branching.invert());
		} else {
			int targetIndex = targetMethod.instructions.indexOf(target);
			if (targetIndex < 0)
				return;

			ReFrame targetFrame = (ReFrame) getFrames()[targetIndex];
			if (targetFrame != null)
				targetFrame.addBranchingBehavior(branching);
		}
	}
}

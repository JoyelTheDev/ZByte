package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import java.util.*;

import dev.joyel.zbyte.reanalysis.AnalysisSupport;
import dev.joyel.zbyte.reanalysis.ReAnalyzer;
import dev.joyel.zbyte.reanalysis.ReInterpreter;
import dev.joyel.zbyte.reanalysis.TransformationException;
import dev.joyel.zbyte.reanalysis.value.ArrayValue;
import dev.joyel.zbyte.reanalysis.value.IntValue;
import dev.joyel.zbyte.reanalysis.value.ReValue;
import dev.joyel.zbyte.utils.AsmFlowUtil;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;

import static dev.joyel.zbyte.utils.AsmFlowUtil.*;
import static dev.joyel.zbyte.transformers.universal.OpaqueConstantFoldingTransformer.toInsn;

public class StaticValueInliningTransformer extends Transformer<TransformerConfig> {
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
	private StaticValueCollectionTransformer staticValueCollector;

		private void run() throws Exception {
		context = new AnalysisSupport(classes, classpath);
		staticValueCollector = new StaticValueCollectionTransformer();
		staticValueCollector.prepare(context);
		for (ClassNode classNode : new ArrayList<>(classNodes()))
			staticValueCollector.collect(classNode);
		for (ClassNode classNode : new ArrayList<>(classNodes()))
			transformClass(classNode);
	}

	private void transformClass(ClassNode node) throws TransformationException {
		boolean dirty = false;
		Set<String> initializationMethods = collectInitializationMethods(node);
		for (MethodNode method : node.methods) {

			if (method.instructions == null || initializationMethods.contains(method.name + method.desc))
				continue;

			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction.getOpcode() != Opcodes.GETSTATIC || !(instruction instanceof FieldInsnNode fieldInsn))
					continue;

				if (!fieldInsn.owner.equals(node.name) && staticValueCollector.isInitializationObservable(fieldInsn.owner))
					continue;

				ReValue value = staticValueCollector.getStaticValue(fieldInsn.owner, fieldInsn.name, fieldInsn.desc);
				AbstractInsnNode replacement = fieldInsn.desc.startsWith("[") ? null : toInsn(value);
				if (replacement != null) {
					method.instructions.set(instruction, replacement);
					dirty = true;
				}
			}

			Frame<ReValue>[] frames;
			try {
				ReAnalyzer analyzer = context.newAnalyzer(node, method);
				ReInterpreter interpreter = analyzer.getInterpreter();
				interpreter.setGetStaticLookup(staticValueCollector);
				frames = analyzer.analyze(node.name, method);
			} catch (Throwable ignored) {

				continue;
			}

			AbstractInsnNode[] instructions = method.instructions.toArray();
			for (int i = 0; i < instructions.length; i++) {
				AbstractInsnNode instruction = instructions[i];
				if (!isArrayLoad(instruction.getOpcode()))
					continue;

				Frame<ReValue> frame = frames[i];
				if (frame == null || frame.getStackSize() < 2)
					continue;

				ReValue indexValue = frame.getStack(frame.getStackSize() - 1);
				if (!(indexValue instanceof IntValue intIndex) || intIndex.value().isEmpty())
					continue;

				ReValue arrayValue = frame.getStack(frame.getStackSize() - 2);
				if (!(arrayValue instanceof ArrayValue array)
						|| !array.isNotNull()
						|| array.dimensions() != 1
						|| array.getFirstDimensionLength().isEmpty())
					continue;

				int index = intIndex.value().getAsInt();
				if (index < 0 || index >= array.getFirstDimensionLength().getAsInt())
					continue;

				ReValue elementValue = array.getValue(index);
				AbstractInsnNode replacement = toInsn(elementValue);
				if (replacement == null)
					continue;

				method.instructions.insertBefore(instruction, new InsnNode(Opcodes.POP2));
				method.instructions.set(instruction, replacement);
				dirty = true;
			}
		}

		if (dirty)
			markChange();
	}

	private static Set<String> collectInitializationMethods(ClassNode node) {
		Map<String, MethodNode> methodsByKey = new HashMap<>();
		for (MethodNode method : node.methods)
			methodsByKey.put(method.name + method.desc, method);

		Set<String> reachable = new HashSet<>();
		Deque<MethodNode> queue = new ArrayDeque<>();
		MethodNode clinit = methodsByKey.get("<clinit>()V");
		if (clinit != null) {
			reachable.add("<clinit>()V");
			queue.add(clinit);
		}

		while (!queue.isEmpty()) {
			MethodNode method = queue.poll();
			if (method.instructions == null)
				continue;
			for (AbstractInsnNode instruction : method.instructions) {
				if (!(instruction instanceof MethodInsnNode methodInsn) || !methodInsn.owner.equals(node.name))
					continue;
				String key = methodInsn.name + methodInsn.desc;
				MethodNode target = methodsByKey.get(key);
				if (target != null && reachable.add(key))
					queue.add(target);
			}
		}
		return reachable;
	}
}

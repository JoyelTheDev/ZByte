package dev.joyel.zbyte.analysis.lookup;

import org.objectweb.asm.tree.MethodInsnNode;
import dev.joyel.zbyte.analysis.value.ReValue;

import java.util.List;

public interface InvokeVirtualLookup {

	ReValue get(MethodInsnNode method, ReValue context, List<? extends ReValue> values);

	boolean hasLookup(MethodInsnNode method);
}

package dev.joyel.zbyte.reanalysis.lookup;

import org.objectweb.asm.tree.MethodInsnNode;
import dev.joyel.zbyte.reanalysis.value.ReValue;

import java.util.List;

public interface InvokeStaticLookup {

	ReValue get(MethodInsnNode method, List<? extends ReValue> values);

	boolean hasLookup(MethodInsnNode method);
}

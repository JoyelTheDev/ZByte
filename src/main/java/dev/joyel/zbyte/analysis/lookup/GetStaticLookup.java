package dev.joyel.zbyte.analysis.lookup;

import org.objectweb.asm.tree.FieldInsnNode;
import dev.joyel.zbyte.analysis.value.ReValue;

public interface GetStaticLookup {

	ReValue get(FieldInsnNode field);

	boolean hasLookup(FieldInsnNode field);
}

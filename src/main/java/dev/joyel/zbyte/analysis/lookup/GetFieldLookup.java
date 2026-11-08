package dev.joyel.zbyte.analysis.lookup;

import org.objectweb.asm.tree.FieldInsnNode;
import dev.joyel.zbyte.analysis.value.ReValue;

public interface GetFieldLookup {

	ReValue get(FieldInsnNode field, ReValue context);

	boolean hasLookup(FieldInsnNode field);
}

package dev.joyel.zbyte.reanalysis.lookup;

import org.objectweb.asm.tree.FieldInsnNode;
import dev.joyel.zbyte.reanalysis.value.ReValue;

public interface GetFieldLookup {

	ReValue get(FieldInsnNode field, ReValue context);

	boolean hasLookup(FieldInsnNode field);
}

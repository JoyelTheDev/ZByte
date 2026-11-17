package dev.joyel.zbyte.reanalysis.lookup;

import org.objectweb.asm.tree.FieldInsnNode;
import dev.joyel.zbyte.reanalysis.value.ReValue;

public interface GetStaticLookup {

	ReValue get(FieldInsnNode field);

	boolean hasLookup(FieldInsnNode field);
}

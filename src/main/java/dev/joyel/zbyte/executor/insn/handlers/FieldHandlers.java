package dev.joyel.zbyte.executor.insn.handlers;

import static org.objectweb.asm.Opcodes.*;

import dev.joyel.zbyte.executor.Context;
import dev.joyel.zbyte.executor.MethodExecutor;
import dev.joyel.zbyte.executor.insn.ExecutionFrame;
import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.values.JavaArray;
import dev.joyel.zbyte.executor.values.JavaObject;
import dev.joyel.zbyte.executor.values.JavaValue;
import dev.joyel.zbyte.utils.TypeStore;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.AbstractMap;
import java.util.Map.Entry;

public final class FieldHandlers {
    private FieldHandlers() {
    }

    public static void register(InsnDispatcher dispatcher) {
        dispatcher.set((f, i) -> read(f, (FieldInsnNode) i, null), GETSTATIC);
        dispatcher.set((f, i) -> {
            JavaValue instance = f.stack.remove(0);
            read(f, (FieldInsnNode) i, instance);
        }, GETFIELD);
        dispatcher.set((f, i) -> {
            JavaValue value = StackOps.popWide(f.stack);
            write(f, (FieldInsnNode) i, null, value);
        }, PUTSTATIC);
        dispatcher.set((f, i) -> {
            JavaValue value = StackOps.popWide(f.stack);
            JavaValue instance = f.stack.remove(0);
            write(f, (FieldInsnNode) i, instance, value);
        }, PUTFIELD);
    }

    @SuppressWarnings("unchecked")
    private static void read(ExecutionFrame f, FieldInsnNode cast, JavaValue instance) {
        Context context = f.context;
        Type type = Type.getType(cast.desc);
        Object provided = context.provider.getField(cast.owner, cast.name, cast.desc, instance, context);
        if (provided == null && type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY) {
            ClassNode fieldClass = null;
            for (ClassNode cn : context.dictionary.values()) {
                if (cn.name.equals(cast.owner)) {
                    fieldClass = cn;
                    break;
                }
            }
            provided = fieldClass.fields.stream()
                    .filter(field -> field.name.equals(cast.name) && field.desc.equals(cast.desc))
                    .findFirst().orElse(null).value;
            if (type.getSort() == Type.BOOLEAN) {
                provided = (Integer) provided != 0;
            }
            context.provider.setField(cast.owner, cast.name, cast.desc, instance, provided, context);
        }
        StackOps.push(f.stack, type.getSort(), provided, (stack, value) -> {
            Entry<Object, String> stored = TypeStore.getFieldFromStore(cast.owner, cast.name, cast.desc, instance);
            if (value != null && value.getClass().isArray()) {
                if (stored != null) {
                    Entry<Object, String[]> entry = (Entry<Object, String[]>) stored.getKey();
                    stack.add(0, new JavaArray(entry.getKey(), entry.getValue()));
                } else {
                    stack.add(0, new JavaArray(value));
                }
            } else if (stored == null) {
                stack.add(0, JavaValue.valueOf(value));
            } else {
                stack.add(0, new JavaObject(value, stored.getValue()));
            }
        });
    }

    private static void write(ExecutionFrame f, FieldInsnNode cast, JavaValue instance, JavaValue value) {
        if (value instanceof JavaArray) {
            TypeStore.setFieldToStore(cast.owner, cast.name, cast.desc, instance,
                    new AbstractMap.SimpleEntry<>(((JavaArray) value).getObjectArrayWithValues(), value.type()));
        } else if (value instanceof JavaObject) {
            TypeStore.setFieldToStore(cast.owner, cast.name, cast.desc, instance,
                    new AbstractMap.SimpleEntry<>(MethodExecutor.convert(MethodExecutor.value(value), cast.desc), value.type()));
        }
        f.context.provider.setField(cast.owner, cast.name, cast.desc, instance, MethodExecutor.convert(MethodExecutor.value(value), cast.desc), f.context);
    }
}

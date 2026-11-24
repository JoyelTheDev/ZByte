package dev.joyel.zbyte.executor.ssvm;

import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.execution.ExecutionContext;
import dev.xdark.ssvm.mirror.InstanceJavaClass;
import dev.xdark.ssvm.value.DoubleValue;
import dev.xdark.ssvm.value.FloatValue;
import dev.xdark.ssvm.value.IntValue;
import dev.xdark.ssvm.value.LongValue;
import dev.xdark.ssvm.value.ObjectValue;
import dev.xdark.ssvm.value.TopValue;
import dev.xdark.ssvm.value.Value;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

final class SsvmBridge {

    private final VirtualMachine vm;

    SsvmBridge() {
        this.vm = new VirtualMachine();
        this.vm.bootstrap();
    }

    SsvmBridge(VirtualMachine vm) {
        this.vm = vm;
    }

    boolean supports(String owner, String name, String desc, boolean isStatic) {
        InstanceJavaClass klass = find(owner);
        if (klass == null) {
            return false;
        }
        return isStatic
                ? klass.getStaticMethod(name, desc) != null
                : klass.getVirtualMethod(name, desc) != null;
    }

    Object invoke(String owner, String name, String desc, Object receiver, Object[] args) {
        InstanceJavaClass klass = find(owner);
        if (klass == null) {
            throw new IllegalStateException("SSVM cannot find " + owner);
        }
        Type[] params = Type.getArgumentTypes(desc);
        List<Value> locals = new ArrayList<>();
        if (receiver != null) {
            locals.add(toValue(receiver, Type.getObjectType(owner)));
        }
        for (int i = 0; i < params.length; i++) {
            locals.add(toValue(args[i], params[i]));
            int sort = params[i].getSort();
            if (sort == Type.LONG || sort == Type.DOUBLE) {
                locals.add(TopValue.INSTANCE);
            }
        }
        Value[] frame = locals.toArray(new Value[0]);
        Value[] stack = new Value[0];
        ExecutionContext context = receiver == null
                ? vm.getHelper().invokeStatic(klass, name, desc, stack, frame)
                : vm.getHelper().invokeVirtual(name, desc, stack, frame);
        return fromValue(context.getResult(), Type.getReturnType(desc));
    }

    private InstanceJavaClass find(String owner) {
        try {
            return (InstanceJavaClass) vm.findBootstrapClass(owner, true);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private Value toValue(Object raw, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                return new IntValue(asBoolean(raw) ? 1 : 0);
            case Type.CHAR:
                return new IntValue(raw instanceof Character c ? c : ((Number) raw).intValue());
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return new IntValue(((Number) raw).intValue());
            case Type.LONG:
                return new LongValue(((Number) raw).longValue());
            case Type.FLOAT:
                return new FloatValue(((Number) raw).floatValue());
            case Type.DOUBLE:
                return new DoubleValue(((Number) raw).doubleValue());
            default:
                return raw == null
                        ? vm.getMemoryManager().nullValue()
                        : vm.getHelper().newUtf8((String) raw);
        }
    }

    private Object fromValue(Value value, Type type) {
        switch (type.getSort()) {
            case Type.VOID:
                return null;
            case Type.BOOLEAN:
                return value.asInt() != 0;
            case Type.CHAR:
                return (char) value.asInt();
            case Type.BYTE:
                return (byte) value.asInt();
            case Type.SHORT:
                return (short) value.asInt();
            case Type.INT:
                return value.asInt();
            case Type.LONG:
                return value.asLong();
            case Type.FLOAT:
                return value.asFloat();
            case Type.DOUBLE:
                return value.asDouble();
            default:
                ObjectValue object = (ObjectValue) value;
                return object.isNull() ? null : vm.getHelper().readUtf8(object);
        }
    }

    private static boolean asBoolean(Object raw) {
        return raw instanceof Boolean b ? b : ((Number) raw).intValue() != 0;
    }
}

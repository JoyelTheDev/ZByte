package dev.joyel.zbyte.utils;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import dev.joyel.zbyte.utils.collections.Int2ObjectMap;

import java.util.*;

import static java.util.Collections.*;

public final class AsmFlowUtil implements Opcodes {

    private AsmFlowUtil() {}

    public static boolean isTerminalOrAlwaysTakeFlowControl(int op) {
        return switch (op) {
            case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN,
                    ATHROW, TABLESWITCH, LOOKUPSWITCH, GOTO, JSR -> true;
            default -> false;
        };
    }

    public static boolean isReturn(int op) {
        return switch (op) {
            case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN -> true;
            default -> false;
        };
    }

    public static boolean isReturn(AbstractInsnNode insn) {
        if (insn == null) return false;
        return isReturn(insn.getOpcode());
    }

    public static boolean isFlowControl(AbstractInsnNode insn) {
        if (insn == null) return false;
        int type = insn.getType();
        return type == AbstractInsnNode.JUMP_INSN
                || type == AbstractInsnNode.TABLESWITCH_INSN
                || type == AbstractInsnNode.LOOKUPSWITCH_INSN
                || insn.getOpcode() == ATHROW
                || insn.getOpcode() == RET;
    }

    public static boolean isMetaData(AbstractInsnNode insn) {
        return insn.getType() == AbstractInsnNode.LABEL
                || insn.getType() == AbstractInsnNode.FRAME
                || insn.getType() == AbstractInsnNode.LINE;
    }

    public static AbstractInsnNode getNextInsn(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && isMetaData(next))
            next = next.getNext();
        return next;
    }

    public static AbstractInsnNode getPreviousInsn(AbstractInsnNode insn) {
        AbstractInsnNode prev = insn.getPrevious();
        while (prev != null && isMetaData(prev))
            prev = prev.getPrevious();
        return prev;
    }

    public static void fixMissingVariableLabels(MethodNode method) {
        InsnList instructions = method.instructions;
        if (instructions == null || instructions.size() == 0)
            return;
        List<LocalVariableNode> variables = method.localVariables;
        if (variables == null || variables.isEmpty())
            return;

        LabelNode firstLabel = null;
        LabelNode lastLabel = null;
        for (int i = 0; i < instructions.size(); i++)
            if (instructions.get(i) instanceof LabelNode label) {
                firstLabel = label;
                break;
            }
        for (int i = instructions.size() - 1; i >= 0; i--)
            if (instructions.get(i) instanceof LabelNode label) {
                lastLabel = label;
                break;
            }
        if (firstLabel == null)
            instructions.insert(firstLabel = new LabelNode());
        if (lastLabel == null || lastLabel == firstLabel)
            instructions.add(lastLabel = new LabelNode());

        for (LocalVariableNode variable : variables) {
            int start = instructions.indexOf(variable.start);
            int end = instructions.indexOf(variable.end);
            if (start < 0 || start > end)
                variable.start = firstLabel;
            if (end < 0)
                variable.end = lastLabel;
        }
    }

    public static boolean hasHandlerFlowIntoBlock(MethodNode method, List<AbstractInsnNode> block, boolean includeFirstInsn) {
        int start = includeFirstInsn ? 0 : 1;
        for (TryCatchBlockNode tryCatchBlock : method.tryCatchBlocks)
            if (block.indexOf(tryCatchBlock.handler) >= start)
                return true;
        return false;
    }

    public static boolean hasInboundFlowReferences(MethodNode method, List<AbstractInsnNode> block) {
        Set<LabelNode> labels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode insn : block)
            if (insn.getType() == AbstractInsnNode.LABEL)
                labels.add((LabelNode) insn);
        if (labels.isEmpty())
            return false;

        for (AbstractInsnNode insn : method.instructions) {
            if (block.contains(insn))
                continue;
            if (insn instanceof JumpInsnNode jump && block.contains(jump.label))
                return true;
            if (insn instanceof TableSwitchInsnNode tswitch) {
                if (labels.contains(tswitch.dflt)) return true;
                for (LabelNode label : tswitch.labels)
                    if (labels.contains(label)) return true;
            }
            if (insn instanceof LookupSwitchInsnNode lswitch) {
                if (labels.contains(lswitch.dflt)) return true;
                for (LabelNode label : lswitch.labels)
                    if (labels.contains(label)) return true;
            }
        }
        return false;
    }

    public static Type getTypeForVarInsn(VarInsnNode varInsn) {
        return switch (varInsn.getOpcode()) {
            case ISTORE, ILOAD -> Type.INT_TYPE;
            case LSTORE, LLOAD -> Type.LONG_TYPE;
            case FSTORE, FLOAD -> Type.FLOAT_TYPE;
            case DSTORE, DLOAD -> Type.DOUBLE_TYPE;
            default -> Type.getObjectType("java/lang/Object");
        };
    }

    public static boolean isConstIntValue(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == LDC && ((LdcInsnNode) insn).cst instanceof Integer)
            return true;
        return (op >= ICONST_M1 && op <= ICONST_5) || op == SIPUSH || op == BIPUSH;
    }

    public static AbstractInsnNode getNextFollowGoto(AbstractInsnNode insn) {
        AbstractInsnNode next = getNextInsn(insn);
        AbstractInsnNode cycleCheckpoint = next;
        long cycleCheckpointRange = 1;
        long stepsSinceCycleCheckpoint = 0;
        while (next != null && next.getOpcode() == GOTO) {
            JumpInsnNode jin = (JumpInsnNode) next;
            next = getNextInsn(jin.label);
            if (next == null || next.getOpcode() != GOTO)
                return next;
            if (next == cycleCheckpoint)
                return null;
            if (++stepsSinceCycleCheckpoint == cycleCheckpointRange) {
                cycleCheckpoint = next;
                stepsSinceCycleCheckpoint = 0;
                cycleCheckpointRange <<= 1;
            }
        }
        return next;
    }

    public static String describeInsn(AbstractInsnNode insn) {
        return switch (insn.getType()) {
            case AbstractInsnNode.INT_INSN -> insn.getOpcode() + " " + ((IntInsnNode) insn).operand;
            case AbstractInsnNode.VAR_INSN -> insn.getOpcode() + " " + ((VarInsnNode) insn).var;
            case AbstractInsnNode.TYPE_INSN -> insn.getOpcode() + " " + ((TypeInsnNode) insn).desc;
            case AbstractInsnNode.FIELD_INSN -> {
                FieldInsnNode f = (FieldInsnNode) insn;
                yield insn.getOpcode() + " " + f.owner + "." + f.name + " " + f.desc;
            }
            case AbstractInsnNode.METHOD_INSN -> {
                MethodInsnNode m = (MethodInsnNode) insn;
                yield insn.getOpcode() + " " + m.owner + "." + m.name + m.desc;
            }
            case AbstractInsnNode.INVOKE_DYNAMIC_INSN -> {
                InvokeDynamicInsnNode id = (InvokeDynamicInsnNode) insn;
                yield insn.getOpcode() + " " + id.name + id.desc;
            }
            case AbstractInsnNode.LDC_INSN -> insn.getOpcode() + " " + ((LdcInsnNode) insn).cst;
            case AbstractInsnNode.IINC_INSN -> {
                IincInsnNode ii = (IincInsnNode) insn;
                yield insn.getOpcode() + " " + ii.var + " " + ii.incr;
            }
            case AbstractInsnNode.MULTIANEWARRAY_INSN -> {
                MultiANewArrayInsnNode m = (MultiANewArrayInsnNode) insn;
                yield insn.getOpcode() + " " + m.desc + " " + m.dims;
            }
            default -> String.valueOf(insn.getOpcode());
        };
    }

        public static VarInsnNode createVarLoad(int index, Type variableType) {
            return createVarLoad(index, variableType.getSort());
        }

        public static VarInsnNode createVarLoad(int index, int typeSort) {
            return switch (typeSort) {
                case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> new VarInsnNode(ILOAD, index);
                case Type.FLOAT -> new VarInsnNode(FLOAD, index);
                case Type.DOUBLE -> new VarInsnNode(DLOAD, index);
                case Type.LONG -> new VarInsnNode(LLOAD, index);
                case Type.ARRAY, Type.OBJECT -> new VarInsnNode(ALOAD, index);
                default -> throw new IllegalArgumentException("Unsupported variable type sort: " + typeSort);
            };
        }

        public static VarInsnNode createVarStore(int index, Type variableType) {
            return createVarStore(index, variableType.getSort());
        }

        public static VarInsnNode createVarStore(int index, int typeSort) {
            return switch (typeSort) {
                case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> new VarInsnNode(ISTORE, index);
                case Type.FLOAT -> new VarInsnNode(FSTORE, index);
                case Type.DOUBLE -> new VarInsnNode(DSTORE, index);
                case Type.LONG -> new VarInsnNode(LSTORE, index);
                case Type.ARRAY, Type.OBJECT -> new VarInsnNode(ASTORE, index);
                default -> throw new IllegalArgumentException("Unsupported variable type sort: " + typeSort);
            };
        }

        public static AbstractInsnNode doubleToInsn(double value) {
            if (value == 0)
                return new InsnNode(DCONST_0);
            if (value == 1)
                return new InsnNode(DCONST_1);
            return new LdcInsnNode(value);
        }

        public static AbstractInsnNode floatToInsn(float value) {
            if (value == 0)
                return new InsnNode(FCONST_0);
            if (value == 1)
                return new InsnNode(FCONST_1);
            if (value == 2)
                return new InsnNode(FCONST_2);
            return new LdcInsnNode(value);
        }

        public static int getSizeConsumed(AbstractInsnNode insn) {

            int op = insn.getOpcode();
            int type = insn.getType();
            if (type == AbstractInsnNode.MULTIANEWARRAY_INSN) {
                return ((MultiANewArrayInsnNode) insn).dims;
            } else if (type == AbstractInsnNode.METHOD_INSN) {
                MethodInsnNode min = (MethodInsnNode) insn;
                Type methodType = Type.getMethodType(min.desc);
                int count = op == INVOKESTATIC ? 0 : 1;
                for (Type argType : methodType.getArgumentTypes()) {
                    count += argType.getSize();
                }
                return count;
            } else if (type == AbstractInsnNode.INVOKE_DYNAMIC_INSN) {
                Type methodType = Type.getMethodType(((InvokeDynamicInsnNode) insn).desc);
                int count = 0;
                for (Type argType : methodType.getArgumentTypes()) {
                    count += argType.getSize();
                }
                return count;
            } else if (type == AbstractInsnNode.FIELD_INSN) {
                FieldInsnNode fin = (FieldInsnNode) insn;
                if (op == GETSTATIC)
                    return 0;
                if (op == GETFIELD)
                    return 1;

                if (op == PUTSTATIC)
                    return Type.getType(fin.desc).getSize();
                if (op == PUTFIELD)
                    return 1 + Type.getType(fin.desc).getSize();
            } else if (type == AbstractInsnNode.FRAME ||
                    type == AbstractInsnNode.LABEL ||
                    type == AbstractInsnNode.LINE) {
                return 0;
            }

            switch (op) {

                case NOP:
                case ACONST_NULL:
                case ICONST_M1:
                case ICONST_0:
                case ICONST_1:
                case ICONST_2:
                case ICONST_3:
                case ICONST_4:
                case ICONST_5:
                case LCONST_0:
                case LCONST_1:
                case FCONST_0:
                case FCONST_1:
                case FCONST_2:
                case DCONST_0:
                case DCONST_1:
                    return 0;

                case BIPUSH:
                case SIPUSH:
                    return 0;

                case LDC:
                    return 0;

                case ILOAD:
                case LLOAD:
                case FLOAD:
                case DLOAD:
                case ALOAD:
                    return 0;

                case IALOAD:
                case LALOAD:
                case FALOAD:
                case DALOAD:
                case AALOAD:
                case BALOAD:
                case CALOAD:
                case SALOAD:
                    return 2;

                case ISTORE:
                case FSTORE:
                case ASTORE:
                    return 1;
                case DSTORE:
                case LSTORE:
                    return 2;

                case IASTORE:
                case FASTORE:
                case AASTORE:
                case BASTORE:
                case CASTORE:
                case SASTORE:
                    return 3;
                case DASTORE:
                case LASTORE:
                    return 4;
                case POP:
                    return 1;
                case POP2:
                    return 2;
                case DUP:
                case DUP_X1:
                case DUP_X2:
                case DUP2:
                case DUP2_X1:
                case DUP2_X2:
                case SWAP:
                    return 0;
                case IADD:
                case FADD:
                case ISUB:
                case FSUB:
                case IMUL:
                case FMUL:
                case IDIV:
                case FDIV:
                case IREM:
                case FREM:
                case ISHL:
                case ISHR:
                case IUSHR:
                case IAND:
                case IXOR:
                case IOR:
                    return 2;
                case LUSHR:
                case LSHR:
                case LSHL:
                    return 3;
                case DREM:
                case DDIV:
                case DMUL:
                case DSUB:
                case DADD:
                case LREM:
                case LDIV:
                case LMUL:
                case LSUB:
                case LADD:
                case LAND:
                case LOR:
                case LXOR:
                    return 4;
                case INEG:
                case FNEG:
                    return 1;
                case DNEG:
                case LNEG:
                    return 2;

                case IINC:
                    return 0;

                case I2L:
                case I2F:
                case I2D:
                case F2I:
                case F2L:
                case F2D:
                case I2B:
                case I2C:
                case I2S:
                    return 1;
                case D2I:
                case D2L:
                case D2F:
                case L2I:
                case L2F:
                case L2D:
                    return 2;
                case FCMPL:
                case FCMPG:
                    return 2;
                case LCMP:
                case DCMPL:
                case DCMPG:
                    return 4;

                case IFEQ:
                case IFNE:
                case IFLT:
                case IFGE:
                case IFGT:
                case IFLE:
                case IFNULL:
                case IFNONNULL:
                    return 1;
                case IF_ICMPEQ:
                case IF_ICMPNE:
                case IF_ICMPLT:
                case IF_ICMPGE:
                case IF_ICMPGT:
                case IF_ICMPLE:
                case IF_ACMPEQ:
                case IF_ACMPNE:
                    return 2;
                case GOTO:
                    return 0;
                case JSR:
                    return 0;

                case RET:
                    return 0;

                case TABLESWITCH:
                case LOOKUPSWITCH:
                    return 1;

                case IRETURN:
                case FRETURN:
                case ARETURN:
                    return 1;
                case LRETURN:
                case DRETURN:
                    return 2;
                case RETURN:
                    return 0;

                case NEW:
                    return 0;

                case NEWARRAY:
                    return 1;

                case ANEWARRAY:
                    return 1;

                case ARRAYLENGTH:
                    return 1;
                case ATHROW:
                    return 1;

                case CHECKCAST:
                    return 0;
                case INSTANCEOF:
                    return 1;

                case MONITORENTER:
                case MONITOREXIT:
                    return 1;
                default:
                    throw new IllegalArgumentException("Unhandled instruction: " + op);
            }
        }

        public static int getSizeProduced(AbstractInsnNode insn) {
            int op = insn.getOpcode();
            int type = insn.getType();
            if (type == AbstractInsnNode.METHOD_INSN) {
                Type methodType = Type.getMethodType(((MethodInsnNode) insn).desc);
                return methodType.getReturnType().getSize();
            } else if (type == AbstractInsnNode.INVOKE_DYNAMIC_INSN) {
                Type methodType = Type.getMethodType(((InvokeDynamicInsnNode) insn).desc);
                return methodType.getReturnType().getSize();
            } else if (type == AbstractInsnNode.FIELD_INSN) {
                FieldInsnNode fin = (FieldInsnNode) insn;
                Type fieldtype = Type.getType(fin.desc);
                if (op == GETSTATIC)
                    return fieldtype.getSize();
                if (op == GETFIELD)
                    return fieldtype.getSize();
                if (op == PUTSTATIC || op == PUTFIELD)
                    return 0;
            } else if (type == AbstractInsnNode.LDC_INSN) {
                Object cst = ((LdcInsnNode) insn).cst;
                return (cst instanceof Double || cst instanceof Long) ? 2 : 1;
            } else if (type == AbstractInsnNode.FRAME ||
                    type == AbstractInsnNode.LABEL ||
                    type == AbstractInsnNode.LINE) {
                return 0;
            } else if (type == AbstractInsnNode.JUMP_INSN) {
                return op == JSR ? 1 : 0;
            }

            switch (op) {

                case NOP:
                    return 0;
                case ACONST_NULL:
                case ICONST_M1:
                case ICONST_0:
                case ICONST_1:
                case ICONST_2:
                case ICONST_3:
                case ICONST_4:
                case ICONST_5:
                case FCONST_0:
                case FCONST_1:
                case FCONST_2:
                    return 1;
                case LCONST_0:
                case LCONST_1:
                case DCONST_0:
                case DCONST_1:
                    return 2;

                case BIPUSH:
                case SIPUSH:
                    return 1;

                case ILOAD:
                case FLOAD:
                case ALOAD:
                    return 1;
                case LLOAD:
                case DLOAD:
                    return 2;

                case IALOAD:
                case FALOAD:
                case AALOAD:
                case BALOAD:
                case CALOAD:
                case SALOAD:
                    return 1;
                case LALOAD:
                case DALOAD:
                    return 2;

                case ISTORE:
                case LSTORE:
                case FSTORE:
                case DSTORE:
                case ASTORE:
                    return 0;

                case IASTORE:
                case LASTORE:
                case FASTORE:
                case DASTORE:
                case AASTORE:
                case BASTORE:
                case CASTORE:
                case SASTORE:
                    return 0;
                case POP:
                case POP2:
                    return 0;
                case DUP:
                case DUP_X1:
                case DUP_X2:
                    return 1;
                case DUP2:
                case DUP2_X1:
                case DUP2_X2:
                    return 2;
                case SWAP:
                    return 0;
                case IADD:
                case FADD:
                case ISUB:
                case FSUB:
                case IMUL:
                case FMUL:
                case IDIV:
                case FDIV:
                case IREM:
                case FREM:
                case INEG:
                case FNEG:
                case ISHL:
                case ISHR:
                case IUSHR:
                case IAND:
                case IOR:
                case IXOR:
                    return 1;
                case LDIV:
                case LMUL:
                case LSUB:
                case LADD:
                case LREM:
                case LNEG:
                case LSHL:
                case LSHR:
                case LUSHR:
                case LAND:
                case LOR:
                case LXOR:
                case DADD:
                case DSUB:
                case DMUL:
                case DDIV:
                case DREM:
                case DNEG:
                    return 2;

                case IINC:
                    return 0;

                case I2F:
                case L2I:
                case L2F:
                case F2I:
                case D2I:
                case D2F:
                case I2B:
                case I2C:
                case I2S:
                    return 1;
                case I2D:
                case L2D:
                case F2D:
                case F2L:
                case D2L:
                case I2L:
                    return 2;
                case LCMP:
                case FCMPL:
                case FCMPG:
                case DCMPL:
                case DCMPG:
                    return 1;

                case RET:
                    return 0;

                case TABLESWITCH:
                case LOOKUPSWITCH:
                    return 0;

                case IRETURN:
                case LRETURN:
                case FRETURN:
                case DRETURN:
                case ARETURN:
                case RETURN:
                    return 0;

                case NEW:
                    return 1;

                case NEWARRAY:
                    return 1;

                case ANEWARRAY:
                    return 1;

                case ARRAYLENGTH:
                    return 1;
                case ATHROW:
                    return 0;

                case CHECKCAST:
                    return 0;
                case INSTANCEOF:
                    return 1;

                case MONITORENTER:
                    return 0;
                case MONITOREXIT:
                    return 0;

                case MULTIANEWARRAY:
                    return 1;
                default:
                    throw new IllegalArgumentException("Unhandled instruction: " + op);
            }
        }

        public static Map<AbstractInsnNode, Integer> indexInstructions(InsnList instructions) {
            Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>(instructions.size());
            int size = instructions.size();
            for (int i = 0; i < size; i++)
                indices.put(instructions.get(i), i);
            return indices;
        }

        public static AbstractInsnNode intToInsn(int value) {
            switch (value) {
                case -1:
                    return new InsnNode(ICONST_M1);
                case 0:
                    return new InsnNode(ICONST_0);
                case 1:
                    return new InsnNode(ICONST_1);
                case 2:
                    return new InsnNode(ICONST_2);
                case 3:
                    return new InsnNode(ICONST_3);
                case 4:
                    return new InsnNode(ICONST_4);
                case 5:
                    return new InsnNode(ICONST_5);
                default:
                    if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                        return new IntInsnNode(BIPUSH, value);
                    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                        return new IntInsnNode(SIPUSH, value);
                    } else {
                        return new LdcInsnNode(value);
                    }
            }
        }

        public static boolean isArrayLoad(int opcode) {
            return switch (opcode) {
                case IALOAD, LALOAD, FALOAD, DALOAD,
                     AALOAD, BALOAD, CALOAD, SALOAD -> true;
                default -> false;
            };
        }

        public static boolean isArrayStore(int opcode) {
            return switch (opcode) {
                case IASTORE, LASTORE, FASTORE, DASTORE,
                     AASTORE, BASTORE, CASTORE, SASTORE -> true;
                default -> false;
            };
        }

        public static boolean isConstValue(int op) {
            return op >= ACONST_NULL && op <= LDC;
        }

        public static boolean isIntBinaryOperation(int op) {
            return switch (op) {
                case IADD, ISUB, IMUL, IDIV, IREM,
                     ISHL, ISHR, IUSHR, IAND, IOR, IXOR -> true;
                default -> false;
            };
        }

        public static boolean isLabel(AbstractInsnNode insn) {
            if (insn == null)
                return false;
            return insn.getType() == AbstractInsnNode.LABEL;
        }

        public static boolean isSwitchEffectiveGoto(TableSwitchInsnNode switchInsn) {
            LabelNode target = switchInsn.dflt;
            for (LabelNode label : switchInsn.labels)
                if (label != target)
                    return false;
            return true;
        }

        public static boolean isSwitchEffectiveGoto(LookupSwitchInsnNode switchInsn) {
            LabelNode target = switchInsn.dflt;
            for (LabelNode label : switchInsn.labels)
                if (label != target)
                    return false;
            return true;
        }

        public static boolean isVarLoad(int op) {
            return op >= ILOAD && op <= ALOAD;
        }

        public static boolean isVarStore(int op) {
            return op >= ISTORE && op <= ASTORE;
        }

        public static AbstractInsnNode longToInsn(long value) {
            if (value == 0)
                return new InsnNode(LCONST_0);
            if (value == 1)
                return new InsnNode(LCONST_1);
            return new LdcInsnNode(value);
        }

        public static void populateFlowMaps(MethodNode method,
                                            Int2ObjectMap<List<Integer>> successorMap,
                                            Int2ObjectMap<List<Integer>> predecessorMap) {
            populateFlowMaps(method, successorMap, predecessorMap, true);
        }

        public static void populateFlowMaps(MethodNode method,
                                            Int2ObjectMap<List<Integer>> successorMap,
                                            Int2ObjectMap<List<Integer>> predecessorMap,
                                            boolean followExceptionFlow) {
            InsnList instructions = method.instructions;
            int size = instructions.size();
            for (int i = 0; i < size; i++) {
                AbstractInsnNode insn = instructions.get(i);
                if (insn == null)
                    throw new IllegalStateException("You broke the method instruction list");
                List<Integer> ss = new ArrayList<>();
                int op = insn.getOpcode();
                if (op >= IRETURN && op <= RETURN || op == ATHROW) {

                } else if (insn instanceof JumpInsnNode jin) {

                    int target = instructions.indexOf(jin.label);
                    ss.add(target);
                    if (op != GOTO && op != JSR)
                        if (i + 1 < size)
                            ss.add(i + 1);
                } else if (insn instanceof TableSwitchInsnNode tswitch) {

                    ss.add(instructions.indexOf(tswitch.dflt));
                    for (LabelNode label : tswitch.labels)
                        ss.add(instructions.indexOf(label));
                } else if (insn instanceof LookupSwitchInsnNode lswitch) {

                    ss.add(instructions.indexOf(lswitch.dflt));
                    for (LabelNode label : lswitch.labels)
                        ss.add(instructions.indexOf(label));
                } else {

                    if (i + 1 < size)
                        ss.add(i + 1);
                }

                if (followExceptionFlow && canThrow(insn)) {

                    for (TryCatchBlockNode block : method.tryCatchBlocks) {
                        int start = instructions.indexOf(block.start);
                        int end = instructions.indexOf(block.end);
                        if (start <= i && i < end)
                            ss.add(instructions.indexOf(block.handler));
                    }
                }
                successorMap.put(i, ss);
            }

            for (int i = 0; i < size; i++)
                for (int s : successorMap.getOrDefault(i, emptyList()))
                    predecessorMap.computeIfAbsent(s, k -> new ArrayList<>()).add(i);
        }

    public static boolean canThrow(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == ATHROW)
            return true;
        if (insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode)
            return true;
        if (op == NEW || op == ANEWARRAY || op == NEWARRAY || op == MULTIANEWARRAY ||
                op == CHECKCAST || op == INSTANCEOF)
            return true;
        return op == GETFIELD || op == PUTFIELD || op == ARRAYLENGTH ||
                (op >= IALOAD && op <= SASTORE) ||
                op == MONITORENTER || op == MONITOREXIT ||
                op == IDIV || op == IREM || op == LDIV || op == LREM;
    }

    public static AbstractInsnNode getPreviousInsn(AbstractInsnNode insn, boolean skipNop) {
        AbstractInsnNode prev = insn.getPrevious();
        while (prev != null && (isMetaData(prev) || (skipNop && prev.getOpcode() == NOP)))
            prev = prev.getPrevious();
        return prev;
    }
}

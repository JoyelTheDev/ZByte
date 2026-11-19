/*
 * Copyright 2016 Sam Sun <me@samczsun.com>
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package dev.joyel.zbyte.transformers;

import dev.joyel.zbyte.transformers.general.removers.IllegalSignatureRemover;
import dev.joyel.zbyte.transformers.general.removers.IllegalVarargsRemover;
import dev.joyel.zbyte.transformers.general.removers.LineNumberRemover;
import dev.joyel.zbyte.transformers.general.removers.SyntheticBridgeRemover;
import dev.joyel.zbyte.transformers.normalizer.ClassNormalizer;
import dev.joyel.zbyte.transformers.normalizer.SourceFileClassNormalizer;

public class Transformers {
    
    public static class General {
        public static final Class<? extends Transformer> PEEPHOLE_OPTIMIZER = dev.joyel.zbyte.transformers.general.peephole.PeepholeOptimizer.class;

        public static class Removers {
            public static final Class<? extends Transformer> ILLEGAL_VARARGS = IllegalVarargsRemover.class;
            public static final Class<? extends Transformer> LINE_NUMBERS = LineNumberRemover.class;
            public static final Class<? extends Transformer> ILLEGAL_SIGNATURE = IllegalSignatureRemover.class;
            public static final Class<? extends Transformer> SYNTHETIC_BRIDGE = SyntheticBridgeRemover.class;
        }
    }
    
    
    public static class Zelix {
        public static final Class<? extends Transformer> STRING_ENCRYPTION_SIMPLE = dev.joyel.zbyte.transformers.zelix.string.SimpleStringEncryptionTransformer.class;
        public static final Class<? extends Transformer> STRING_ENCRYPTION_ENHANCED = dev.joyel.zbyte.transformers.zelix.string.EnhancedStringEncryptionTransformer.class;
        public static final Class<? extends Transformer> REFLECTION_OBFUSCATION = dev.joyel.zbyte.transformers.zelix.ReflectionObfuscationTransformer.class;
        public static final Class<? extends Transformer> FLOW_OBFUSCATION = dev.joyel.zbyte.transformers.zelix.FlowObfuscationTransformer.class;
    }

    public static class Normalizer {
        public static final Class<? extends Transformer> PACKAGE_NORMALIZER = dev.joyel.zbyte.transformers.normalizer.PackageNormalizer.class;
        public static final Class<? extends Transformer> CLASS_NORMALIZER = ClassNormalizer.class;
        public static final Class<? extends Transformer> FIELD_NORMALIZER = dev.joyel.zbyte.transformers.normalizer.FieldNormalizer.class;
        public static final Class<? extends Transformer> METHOD_NORMALIZER = dev.joyel.zbyte.transformers.normalizer.MethodNormalizer.class;
        public static final Class<? extends Transformer> SOURCEFILE_CLASS_NORMALIZER = SourceFileClassNormalizer.class;
        public static final Class<? extends Transformer> DUPLICATE_RENAMER = dev.joyel.zbyte.transformers.normalizer.DuplicateRenamer.class;
    }

    public static class Universal {
        public static final Class<? extends Transformer> ACCESS_REPAIR = dev.joyel.zbyte.transformers.universal.AccessRepairTransformer.class;
        public static final Class<? extends Transformer> ANNOTATION_FILTER = dev.joyel.zbyte.transformers.universal.AnnotationFilterTransformer.class;
        public static final Class<? extends Transformer> CLASS_FOR_NAME = dev.joyel.zbyte.transformers.universal.ClassForNameTransformer.class;
        public static final Class<? extends Transformer> RECOVER_SYNTHETICS = dev.joyel.zbyte.transformers.universal.RecoverSyntheticsTransformer.class;
        public static final Class<? extends Transformer> TRY_CATCH_REPAIR = dev.joyel.zbyte.transformers.universal.TryCatchRepairTransformer.class;
        public static final Class<? extends Transformer> STRING_BUILDER = dev.joyel.zbyte.transformers.universal.StringBuilderTransformer.class;
        public static final Class<? extends Transformer> INLINE_PURE_FUNCTIONS = dev.joyel.zbyte.transformers.universal.InlinePureFunctionsTransformer.class;
        public static final Class<? extends Transformer> INLINE_CONSTANT_VALUES = dev.joyel.zbyte.transformers.universal.InlineConstantValuesTransformer.class;
        public static final Class<? extends Transformer> EMPTY_ARRAY_LENGTH = dev.joyel.zbyte.transformers.universal.EmptyArrayLengthTransformer.class;
        public static final Class<? extends Transformer> USELESS_POP_CLEAN = dev.joyel.zbyte.transformers.universal.UselessPopCleanTransformer.class;
        public static final Class<? extends Transformer> CLEAN_REDUNDANT_JUMPS = dev.joyel.zbyte.transformers.universal.CleanRedundantJumpsTransformer.class;
        public static final Class<? extends Transformer> CLEAN_REDUNDANT_SWITCHES = dev.joyel.zbyte.transformers.universal.CleanRedundantSwitchesTransformer.class;
        public static final Class<? extends Transformer> NUMBER = dev.joyel.zbyte.transformers.universal.UniversalNumberTransformer.class;
        public static final Class<? extends Transformer> FLOW = dev.joyel.zbyte.transformers.universal.UniversalFlowTransformer.class;
        public static final Class<? extends Transformer> NUMBER_POOL = dev.joyel.zbyte.transformers.universal.UniversalNumberPoolTransformer.class;
        public static final Class<? extends Transformer> STRING_POOL = dev.joyel.zbyte.transformers.universal.UniversalStringPoolTransformer.class;
        public static final Class<? extends Transformer> DUPLICATE_ANNOTATION_REMOVING = dev.joyel.zbyte.transformers.universal.DuplicateAnnotationRemovingTransformer.class;
        public static final Class<? extends Transformer> FRAME_REMOVING = dev.joyel.zbyte.transformers.universal.FrameRemovingTransformer.class;
        public static final Class<? extends Transformer> LONG_ANNOTATION_REMOVING = dev.joyel.zbyte.transformers.universal.LongAnnotationRemovingTransformer.class;
        public static final Class<? extends Transformer> LONG_EXCEPTION_REMOVING = dev.joyel.zbyte.transformers.universal.LongExceptionRemovingTransformer.class;
        public static final Class<? extends Transformer> SYNTHETIC_MODIFIER_REMOVING = dev.joyel.zbyte.transformers.universal.SyntheticModifierRemovingTransformer.class;
        public static final Class<? extends Transformer> UNKNOWN_ATTRIBUTE_REMOVING = dev.joyel.zbyte.transformers.universal.UnknownAttributeRemovingTransformer.class;
        public static final Class<? extends Transformer> VARIABLE_TABLE_NORMALIZING = dev.joyel.zbyte.transformers.universal.VariableTableNormalizingTransformer.class;
        public static final Class<? extends Transformer> DEAD_CODE_REMOVING = dev.joyel.zbyte.transformers.universal.DeadCodeRemovingTransformer.class;
        public static final Class<? extends Transformer> GOTO_INLINING = dev.joyel.zbyte.transformers.universal.GotoInliningTransformer.class;
        public static final Class<? extends Transformer> DUPLICATE_CATCH_MERGING = dev.joyel.zbyte.transformers.universal.DuplicateCatchMergingTransformer.class;
        public static final Class<? extends Transformer> CYCLE_CLASS_REMOVING = dev.joyel.zbyte.transformers.universal.CycleClassRemovingTransformer.class;
        public static final Class<? extends Transformer> UNUSED_METHOD_REMOVER = dev.joyel.zbyte.transformers.universal.UnusedMethodRemoverTransformer.class;
        public static final Class<? extends Transformer> UNUSED_FIELD_REMOVER = dev.joyel.zbyte.transformers.universal.UnusedFieldRemoverTransformer.class;
        public static final Class<? extends Transformer> UNUSED_CLASS_REMOVER = dev.joyel.zbyte.transformers.universal.UnusedClassRemoverTransformer.class;
        public static final Class<? extends Transformer> RESOURCE_DECRYPTION = dev.joyel.zbyte.transformers.universal.ResourceDecryptionTransformer.class;
        public static final Class<? extends Transformer> ENUM_NAME_RESTORATION = dev.joyel.zbyte.transformers.universal.EnumNameRestorationTransformer.class;
        public static final Class<? extends Transformer> OPAQUE_CONSTANT_FOLDING = dev.joyel.zbyte.transformers.universal.OpaqueConstantFoldingTransformer.class;
        public static final Class<? extends Transformer> OPAQUE_PREDICATE_FOLDING = dev.joyel.zbyte.transformers.universal.OpaquePredicateFoldingTransformer.class;
        public static final Class<? extends Transformer> VARIABLE_FOLDING = dev.joyel.zbyte.transformers.universal.VariableFoldingTransformer.class;
        public static final Class<? extends Transformer> STATIC_VALUE_COLLECTION = dev.joyel.zbyte.transformers.universal.StaticValueCollectionTransformer.class;
        public static final Class<? extends Transformer> STATIC_VALUE_INLINING = dev.joyel.zbyte.transformers.universal.StaticValueInliningTransformer.class;
        public static final Class<? extends Transformer> REDUNDANT_TRY_CATCH_REMOVING = dev.joyel.zbyte.transformers.universal.RedundantTryCatchRemovingTransformer.class;
        public static final Class<? extends Transformer> EXCEPTION_COLLECTION = dev.joyel.zbyte.transformers.universal.ExceptionCollectionTransformer.class;
        public static final Class<? extends Transformer> REFLECTION_RESOLVER = dev.joyel.zbyte.transformers.universal.ReflectionResolverTransformer.class;
        public static final Class<? extends Transformer> REFLECTION_RESOLVER_WIDEN = dev.joyel.zbyte.transformers.universal.ReflectionResolverWidenTransformer.class;
        public static final Class<? extends Transformer> METHOD_HANDLE_RESOLVER = dev.joyel.zbyte.transformers.universal.MethodHandleResolverTransformer.class;
        public static final Class<? extends Transformer> INVOKE_DYNAMIC_INLINING = dev.joyel.zbyte.transformers.universal.InvokeDynamicInliningTransformer.class;
        public static final Class<? extends Transformer> CALL_RESULT_INLINING = dev.joyel.zbyte.transformers.universal.CallResultInliningTransformer.class;
        public static final Class<? extends Transformer> ZKM = dev.joyel.zbyte.transformers.zkm.ZkmTransformer.class;
        public static final Class<? extends Transformer> ZKM_PARAMETER_UNPACKING = dev.joyel.zbyte.transformers.zkm.ZkmParameterUnpackingTransformer.class;
        public static final Class<? extends Transformer> ZKM_DECRYPTION_CLEANUP = dev.joyel.zbyte.transformers.zkm.ZkmDecryptionCleanupTransformer.class;
    }
}

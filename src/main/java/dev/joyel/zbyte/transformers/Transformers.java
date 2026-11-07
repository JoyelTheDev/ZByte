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
    public static class Stringer {
        public static final Class<? extends Transformer> STRING_ENCRYPTION = dev.joyel.zbyte.transformers.stringer.StringEncryptionTransformer.class;
        public static final Class<? extends Transformer> INVOKEDYNAMIC = dev.joyel.zbyte.transformers.stringer.InvokedynamicTransformer.class;
        public static final Class<? extends Transformer> REFLECTION_OBFUSCATION = dev.joyel.zbyte.transformers.stringer.ReflectionObfuscationTransformer.class;
        public static final Class<? extends Transformer> HIDEACCESS_OBFUSCATION = dev.joyel.zbyte.transformers.stringer.HideAccessObfuscationTransformer.class;
        public static final Class<? extends Transformer> RESOURCE_ENCRYPTION = dev.joyel.zbyte.transformers.stringer.ResourceEncryptionTransformer.class;
    }
    
    public static class General {
        public static final Class<? extends Transformer> PEEPHOLE_OPTIMIZER = dev.joyel.zbyte.transformers.general.peephole.PeepholeOptimizer.class;

        public static class Removers {
            public static final Class<? extends Transformer> ILLEGAL_VARARGS = IllegalVarargsRemover.class;
            public static final Class<? extends Transformer> LINE_NUMBERS = LineNumberRemover.class;
            public static final Class<? extends Transformer> ILLEGAL_SIGNATURE = IllegalSignatureRemover.class;
            public static final Class<? extends Transformer> SYNTHETIC_BRIDGE = SyntheticBridgeRemover.class;
        }
    }
    
    public static class Smoke {
    	public static final Class<? extends Transformer> STRING_ENCRYPTION = dev.joyel.zbyte.transformers.smoke.StringEncryptionTransformer.class;
        public static final Class<? extends Transformer> NUMBER_OBFUSCATION = dev.joyel.zbyte.transformers.smoke.NumberObfuscationTransformer.class;
        public static final Class<? extends Transformer> ILLEGAL_VARIABLE = dev.joyel.zbyte.transformers.smoke.IllegalVariableTransformer.class;
    }
    
    public static class Zelix {
        public static final Class<? extends Transformer> STRING_ENCRYPTION_SIMPLE = dev.joyel.zbyte.transformers.zelix.string.SimpleStringEncryptionTransformer.class;
        public static final Class<? extends Transformer> STRING_ENCRYPTION_ENHANCED = dev.joyel.zbyte.transformers.zelix.string.EnhancedStringEncryptionTransformer.class;
        public static final Class<? extends Transformer> REFLECTION_OBFUSCATION = dev.joyel.zbyte.transformers.zelix.ReflectionObfuscationTransformer.class;
        public static final Class<? extends Transformer> FLOW_OBFUSCATION = dev.joyel.zbyte.transformers.zelix.FlowObfuscationTransformer.class;
    }

    public static class SkidSuite {
        public static final Class<? extends Transformer> STRING_ENCRYPTION = dev.joyel.zbyte.transformers.skidsuite2.StringEncryptionTransformer.class;
        public static final Class<? extends Transformer> FAKE_EXCEPTION = dev.joyel.zbyte.transformers.skidsuite2.FakeExceptionTransformer.class;
    }

    public static class Normalizer {
        public static final Class<? extends Transformer> PACKAGE_NORMALIZER = dev.joyel.zbyte.transformers.normalizer.PackageNormalizer.class;
        public static final Class<? extends Transformer> CLASS_NORMALIZER = ClassNormalizer.class;
        public static final Class<? extends Transformer> FIELD_NORMALIZER = dev.joyel.zbyte.transformers.normalizer.FieldNormalizer.class;
        public static final Class<? extends Transformer> METHOD_NORMALIZER = dev.joyel.zbyte.transformers.normalizer.MethodNormalizer.class;
        public static final Class<? extends Transformer> SOURCEFILE_CLASS_NORMALIZER = SourceFileClassNormalizer.class;
        public static final Class<? extends Transformer> DUPLICATE_RENAMER = dev.joyel.zbyte.transformers.normalizer.DuplicateRenamer.class;
    }
}

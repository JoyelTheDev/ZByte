/*
 * Copyright 2018 Sam Sun <github-contact@samczsun.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.joyel.zbyte.rules;

import java.util.List;

import com.google.common.collect.ImmutableList;
import dev.joyel.zbyte.rules.generic.RuleIllegalSignature;
import dev.joyel.zbyte.rules.normalizer.RuleSourceFileAttribute;
import dev.joyel.zbyte.rules.zelix.RuleEnhancedStringEncryption;
import dev.joyel.zbyte.rules.zelix.RuleMethodParameterChangeStringEncryption;
import dev.joyel.zbyte.rules.zelix.RuleReflectionDecryptor;
import dev.joyel.zbyte.rules.zelix.RuleSimpleStringEncryption;
import dev.joyel.zbyte.rules.zelix.RuleSuspiciousClinit;
import dev.joyel.zbyte.rules.universal.*;

public class Rules {

    public static final List<Rule> RULES = ImmutableList.of(
            // Generic
            new RuleIllegalSignature(),

            // Normalizer
            new RuleSourceFileAttribute(),


            // Zelix
            new RuleSuspiciousClinit(),
            new RuleReflectionDecryptor(),
            new RuleSimpleStringEncryption(),
            new RuleEnhancedStringEncryption(),
            new RuleMethodParameterChangeStringEncryption(),

            // Dash-O
            new dev.joyel.zbyte.rules.dasho.RuleStringDecryptor(),
            new dev.joyel.zbyte.rules.dasho.RuleFlowObfuscation(),

            // Allatori
            new dev.joyel.zbyte.rules.allatori.RuleStringDecryptor(),
            
            // Universal
            new RuleIllegalAccessFlags(),
            new RuleIllegalAnnotation(),
            new RuleBrokenTryCatch(),
            new RuleMissingSynthetics(),
            new RuleClassForNameJunk(),
            new RuleStringBuilderConstants(),
            new RuleStringPool(),
            new RuleNumberPool(),
            new RuleEmptyArrayLength(),
            new RuleConstantArithmetic(),
            new RuleRedundantJumps(),
            new RuleRedundantSwitches(),
            new RulePureFunctions(),
            new RuleDuplicateAnnotations(),
            new RuleLongAnnotations(),
            new RuleLongExceptions(),
            new RuleAntiTamper(),
            new RuleIllegalClassAttributes(),
            new RuleDeadCode(),
            new RuleDuplicateCatchBlocks(),
            new RuleGotoChains(),
            new RuleCycleClasses(),
            new RuleOpaqueConstants(),
            new RuleOpaquePredicates(),
            new RuleConstantVariables(),
            new RuleStaticConstants(),
            new RuleRedundantTryCatch(),
            new RuleReflectionCalls(),
            new RuleZkmInvokeDynamic()
    );
}

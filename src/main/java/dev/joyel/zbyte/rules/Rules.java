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
import dev.joyel.zbyte.rules.antireleak.RuleInvokeDynamic;
import dev.joyel.zbyte.rules.classguard.RuleClassGuardPackage;
import dev.joyel.zbyte.rules.classguard.RuleEncryptedClass;
import dev.joyel.zbyte.rules.generic.RuleIllegalSignature;
import dev.joyel.zbyte.rules.normalizer.RuleSourceFileAttribute;
import dev.joyel.zbyte.rules.skidsuite2.RuleFakeException;
import dev.joyel.zbyte.rules.smoke.RuleNumberObfuscation;
import dev.joyel.zbyte.rules.special.RuleBisguardClassEncryption;
import dev.joyel.zbyte.rules.special.RuleSuperblaubeereObfuscation;
import dev.joyel.zbyte.rules.stringer.RuleHideAccess;
import dev.joyel.zbyte.rules.stringer.RuleInvokedynamic1;
import dev.joyel.zbyte.rules.stringer.RuleInvokedynamic2;
import dev.joyel.zbyte.rules.stringer.RuleStringDecryptor;
import dev.joyel.zbyte.rules.stringer.RuleStringDecryptorV3;
import dev.joyel.zbyte.rules.stringer.RuleStringDecryptorWithThread;
import dev.joyel.zbyte.rules.zelix.RuleEnhancedStringEncryption;
import dev.joyel.zbyte.rules.zelix.RuleMethodParameterChangeStringEncryption;
import dev.joyel.zbyte.rules.zelix.RuleReflectionDecryptor;
import dev.joyel.zbyte.rules.zelix.RuleSimpleStringEncryption;
import dev.joyel.zbyte.rules.zelix.RuleSuspiciousClinit;
import dev.joyel.zbyte.rules.universal.*;

public class Rules {

    public static final List<Rule> RULES = ImmutableList.of(
            // ClassGuard
            new RuleClassGuardPackage(),
            new RuleEncryptedClass(),

            // Generic
            new RuleIllegalSignature(),

            // Normalizer
            new RuleSourceFileAttribute(),

            // Stringer
            new RuleStringDecryptor(),
            new RuleStringDecryptorWithThread(),
            new RuleStringDecryptorV3(),
            new RuleHideAccess(),
            new RuleInvokedynamic1(),
            new RuleInvokedynamic2(),

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

            // AntiReleak
            new dev.joyel.zbyte.rules.antireleak.RuleStringDecryptor(),
            new RuleInvokeDynamic(),

            // SkidSuite2
            new RuleFakeException(),
            new dev.joyel.zbyte.rules.skidsuite2.RuleStringDecryptor(),

            // Smoke
            new dev.joyel.zbyte.rules.smoke.RuleStringDecryptor(),
            new RuleNumberObfuscation(),

            // Special
            new RuleBisguardClassEncryption(),
            new RuleSuperblaubeereObfuscation(),

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
            new RuleDeadCode(),
            new RuleDuplicateCatchBlocks(),
            new RuleGotoChains(),
            new RuleCycleClasses(),
            new RuleOpaqueConstants(),
            new RuleOpaquePredicates(),
            new RuleConstantVariables(),
            new RuleStaticConstants(),
            new RuleRedundantTryCatch(),
            new RuleReflectionCalls()
    );
}

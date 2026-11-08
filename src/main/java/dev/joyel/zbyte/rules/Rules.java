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
            new RulePureFunctions()
    );
}

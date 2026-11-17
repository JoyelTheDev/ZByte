# Transformers

Use names as `family.Name`. Options go under the name in the config. Defaults are in brackets.

## universal

Work on any jar. Run `detect: true` to see which apply.

**Control flow**

- `UniversalFlowTransformer`: removes branches that always go one way. Start here. `maxPasses` [8], `maxAnalysisInstructions` [0 = no limit]
- `OpaquePredicateFoldingTransformer`, `CleanRedundantJumpsTransformer`, `CleanRedundantSwitchesTransformer`
- `GotoInliningTransformer`, `DeadCodeRemovingTransformer`
- `RedundantTryCatchRemovingTransformer`, `DuplicateCatchMergingTransformer`, `TryCatchRepairTransformer`

**Constants**

- `UniversalNumberTransformer`: `maxPasses` [8], `maxAnalysisInstructions` [0]
- `OpaqueConstantFoldingTransformer`, `InlineConstantValuesTransformer`, `VariableFoldingTransformer`, `StaticValueInliningTransformer`
- `UniversalNumberPoolTransformer`, `UniversalStringPoolTransformer`: inline values kept in a pooled array
- `StringBuilderTransformer`: turns constant `StringBuilder` chains back into strings
- `EmptyArrayLengthTransformer`
- `InlinePureFunctionsTransformer`: `removePureMethods` [true], `maxInstructions` [150]
- `CallResultInliningTransformer`: `evaluateClassInitializers` [true]

**Reflection and indy**

- `ReflectionResolverTransformer`, `ReflectionResolverWidenTransformer`: `widenAccess` [false]
- `ClassForNameTransformer`: removes unused `Class.forName` calls. `classNames` [`java.lang.Integer`], `*` for all
- `InvokeDynamicInliningTransformer`: `maxSteps` [20000]

**Repair and cleanup**

- `AccessRepairTransformer` (`removeSynthetic` [false]), `AnnotationFilterTransformer`, `DuplicateAnnotationRemovingTransformer`
- `LongAnnotationRemovingTransformer`, `LongExceptionRemovingTransformer` (`maxLength` [150])
- `CycleClassRemovingTransformer`, `RecoverSyntheticsTransformer`, `SyntheticModifierRemovingTransformer`
- `UnknownAttributeRemovingTransformer`, `FrameRemovingTransformer`, `UselessPopCleanTransformer`
- `UnusedMethodRemoverTransformer`, `VariableTableNormalizingTransformer`, `EnumNameRestorationTransformer`

## zkm (Zelix KlassMaster 26)

- `zkm.ZkmTransformer`: resolves `invokedynamic` member and call hiding. `cleanFlow` [false], `maxPasses` [8]

## zelix (older Zelix)

- `zelix.StringEncryptionTransformer` (`cleanup` [true]), `zelix.ReflectionObfuscationTransformer` (`cleanup` [true]), `zelix.FlowObfuscationTransformer`
- `zelix.string.SimpleStringEncryptionTransformer`, `zelix.string.EnhancedStringEncryptionTransformer`: see the note below

## dasho, allatori

- `dasho.StringEncryptionTransformer`, `dasho.FlowObfuscationTransformer`, `dasho.TamperObfuscationTransformer`, `dasho.FakeExceptionTransformer`
- `allatori.StringEncryptionTransformer` (`cleanup` [true]), `allatori.FlowObfuscationTransformer`, `allatori.LightFlowObfuscationTransformer`

**Note:** the `*.string.*` variants use the JavaVM library, which loads `rt.jar` from `java.home/lib` (Java 8 only) and isn't covered by the sandbox. Prefer the ones listed above them.

## general

- `general.peephole.PeepholeOptimizer`: runs small clean-ups repeatedly. Good last step.
- `general.removers.*`: `IllegalSignatureRemover`, `IllegalAnnotationRemover`, `IllegalTypeAnnotationRemover`, `IllegalVarargsRemover`, `SyntheticBridgeRemover`, `LineNumberRemover`, `LocalVariableRemover`

## special

`FlowObfuscationTransformer`, `InvalidClassRemover`, `TryCatchFixer`, `UselessArithmeticTransformer`, `WrappedLocalsTransformer`

## normalizer (always last)

- `ClassNormalizer`, `FieldNormalizer`, `MethodNormalizer`, `PackageNormalizer`, `EnumNormalizer`
- `PackageTruncator` (`packageLayers` [15])
- `SourceFileClassNormalizer`: names classes from the `SourceFile` attribute when it survived
- `DuplicateRenamer` (`renameIllegalNames` [false])

The `mapping-file` option is accepted but currently ignored.

## minecraft

`NotchToSrgTransformer`, `SrgToMCPTransformer`, `NotchToIntermediaryTransformer`, `IntermediaryToYarnTransformer`. They read fixed file names from the working directory (`joined.srg`/`joined.tsrg`, `mappings.tiny`, `methods.csv`, `fields.csv`). `reverse` flips direction.

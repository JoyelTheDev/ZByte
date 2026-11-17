# Usage

## Command line

```
java -Xss512m -jar zbyte.jar --config config.yml
```

`-Xss512m` gives the analysis enough stack. Without it, big jars often crash.

If `output` already exists it is renamed to `output.jar.bak`.

## Config options

| Option | Default | Meaning |
| --- | --- | --- |
| `input` | required | Jar to process |
| `output` | required | Where to write the result (not needed with `detect`) |
| `transformers` | required | Transformers to run, in order (not needed with `detect`) |
| `detect` | false | Only print what was found and which transformers to use |
| `path` | none | Jars or folders of jars the input depends on |
| `libraries` | none | Jars the emulator may need to run the input's code |
| `ignoredClasses` | none | Regexes for classes to skip |
| `sandbox` | on | See [Sandbox](sandbox.md) |
| `smartRedo` | false | Repeat each transformer until it changes nothing. Can loop forever |
| `verify` | false | Check every written class with ASM and report failures |
| `patchAsm` | false | Work around jars that crash ASM |
| `deleteUselessClasses` | false | Drop classes whose supertypes can't be found. Only with all libraries supplied |

Add the libraries the jar needs (for a plugin, the server API) to `path` or `libraries`. Many errors come from missing ones.

## Transformers in the config

```yaml
transformers:
  - universal.UniversalFlowTransformer
  - universal.UniversalNumberTransformer:
      maxPasses: 16
  - normalizer.ClassNormalizer: {}
    normalizer.FieldNormalizer: {}
```

Names are relative to `dev.joyel.zbyte.transformers`. Entries written together under one `-` run in order. Names shared by several families (`StringEncryptionTransformer`, `FlowObfuscationTransformer`) need their package, for example `dasho.StringEncryptionTransformer`.

## Order matters

1. Undo obfuscation layers in reverse order. Run a transformer twice if the obfuscator was applied twice.
2. Decrypt first, clean up after.
3. Normalizers last. Decryptors often find things by their obfuscated names.

## Detection

With `detect: true`, ZByte prints each pattern it recognises, and the transformers it suggests. Try the first one listed. It can only find patterns it has rules for, and it doesn't detect plain name obfuscation. Run it again on your output to see the next layer.

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Success |
| 1 | Bad command line |
| 2 | No `--config` |
| 3 | Config could not be read (bad YAML, unknown transformer or option) |
| 4 / 5 / 6 | Missing `input` / `output` / `transformers` |
| -1 | Unexpected failure |
| -2 | A class is missing: add `path` or `libraries` |
| -3 | Stack overflow: raise `-Xss` |

## As a library

```java
Configuration config = new Configuration();
config.setInput(new File("input.jar"));
config.setOutput(new File("output.jar"));
config.setTransformers(List.of(
        TransformerConfig.configFor(UniversalFlowTransformer.class)
));
new ZByte(config).start();
```

To set options, create the transformer's `Config` class (for example `UniversalFlowTransformer.Config`) and use its setters.

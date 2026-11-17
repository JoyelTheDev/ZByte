# ZByte

A Java bytecode deobfuscator. Give it an obfuscated jar and a list of transformers, and it writes a cleaner jar you can read in a decompiler.

ZByte started as a fork of [java-deobfuscator](https://github.com/java-deobfuscator/deobfuscator) by Sam Sun, which is no longer maintained.

## What it can do

- Detect which obfuscation a jar uses and suggest transformers (`detect: true`)
- Decrypt strings by running the obfuscator's own code in an emulator
- Clean up control flow, constants, reflection and junk code in any jar
- Handle Zelix KlassMaster (including ZKM 26 `invokedynamic`), DashO and Allatori
- Rename classes, fields and methods to readable names

Not supported (removed from the original): Stringer, AntiReleak, ClassGuard, SkidSuite2, Smoke.

## Quick start

You need JDK 25.

```bash
git clone https://github.com/OWNER/ZByte.git
cd ZByte
mvn clean package -DskipTests
```

The jar ends up in `target/zbyte-1.0.0.jar`.

Find out what the jar uses. Put this in `detect.yml`:

```yaml
input: input.jar
detect: true
```

```bash
java -jar zbyte.jar --config detect.yml
```

Then write a `config.yml` using the transformers it suggests:

```yaml
input: input.jar
output: output.jar
transformers:
  - universal.UniversalFlowTransformer
  - universal.StringBuilderTransformer
  - normalizer.ClassNormalizer
```

```bash
java  -jar zbyte.jar --config config.yml
```

If the result is still obfuscated, run detection on `output.jar` and go again. Undo layers in the reverse order they were applied.

## Docs

- [Usage](docs/usage.md): options, ordering, library use
- [Transformers](docs/transformers.md): what's available
- [Sandbox](docs/sandbox.md): limits on emulated code
- [Troubleshooting](docs/troubleshooting.md)

## Safety

ZByte runs code from the jar you give it. The [sandbox](docs/sandbox.md) blocks dangerous calls in most cases, but it isn't a security boundary. Use a disposable VM or container for untrusted samples.

Only analyse software you have the right to analyse.

## License

Apache 2.0. Parts come from java-deobfuscator (also Apache 2.0); its copyright notices are kept. See [NOTICE](NOTICE).

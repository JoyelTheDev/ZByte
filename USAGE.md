# Usage
### As a library

```java
public class SomeRandomDeobfuscator {
    public static void main(String[] args) throws Throwable {
        Configuration config = new Configuration();
        config.setInput(new File("input.jar"));
        config.setOutput(new File("output.jar"));
        config.setPath(Arrays.asList(
                new File("C:\\Program Files\\Java\\jdk_8\\jre\\lib\\rt.jar"),
                new File("C:\\Program Files\\Java\\jdk_8\\jre\\lib\\jce.jar"),
                new File("C:\\Program Files\\Java\\jdk_8\\jre\\lib\\ext\\jfxrt.jar"),
                new File("C:\\Program Files\\Java\\jdk_8\\lib\\tools.jar")
        ));
        config.setTransformers(Arrays.asList(
                TransformerConfig.configFor(PeepholeOptimizer.class)
        ));
        new Deobfuscator(config).start();
    }
}
```

### CLI

If you don't want to import the project, you can always use the command line interface.

| Argument | Description |
| --- | --- |
| --config | The configuration file |

You may specify multiple transformers, and they will be applied in the order given. Order does matter as sometimes one transformation depends on another not being present.

If you wish to use one of the default transformers, then you may remove the `dev.joyel.zbyte.transformers` prefix.

Here is a sample `config.yaml`:

```yaml
input: input.jar
output: output.jar
transformers:
  - normalizer.MethodNormalizer:
      mapping-file: normalizer.txt
  - stringer.StringEncryptionTransformer
  - normalizer.ClassNormalizer: {}
    normalizer.FieldNormalizer: {}
```

### Sandbox

Obfuscated code is emulated while transformers decrypt strings and resolve flow. The sandbox blocks dangerous host operations during that emulation and caps runaway execution. It is enabled by default and configured in `config.yaml`:

```yaml
sandbox:
  enabled: true
  logOnly: false
  maxInstructions: 500000000
  timeoutMillis: 60000
  allow:
    - java/io/File.delete
  deny:
    - com/example/
```

| Option | Description |
| --- | --- |
| enabled | Turns the sandbox on or off |
| logOnly | Records violations in the log without blocking them |
| maxInstructions | Instruction budget per execution context, 0 disables the limit |
| timeoutMillis | Wall clock limit per execution context, 0 disables the limit |
| allow | Rules that override the built-in deny list |
| deny | Extra rules added to the built-in deny list |

A rule ending in `/` matches a package prefix, `owner` matches a class, and `owner.method` matches one method (`owner.*` matches all methods). Built-in rules block process execution, networking, file writes and deletes, `System.exit`, native loading, class definition, reflection internals and `sun.misc.Unsafe`. A summary of blocked operations is logged after the transformers finish.

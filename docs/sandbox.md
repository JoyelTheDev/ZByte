# Sandbox

Many transformers run parts of the input jar in an emulator. The sandbox limits what that code can do.

It blocks, and logs, calls to:

- processes (`Runtime`, `ProcessBuilder`) and `System.exit`, `System.load*`
- the network (sockets, `URLConnection`, `InetAddress`)
- file writes and `java/nio/file/`
- environment and system property changes, `getenv`
- class definition, `URLClassLoader`, `Unsafe`, `javax/script`, `java/sql`, `java/rmi`

It also stops emulation after an instruction limit. A summary of anything blocked is printed at the end.

```yaml
sandbox:
  enabled: true
  logOnly: false
  maxInstructions: 500000000
  timeoutMillis: 60000
  allow:
    - java/lang/System.getenv
  deny:
    - java/lang/Thread.sleep
```

| Option | Default |
| --- | --- |
| `enabled` | true |
| `logOnly` | false (true = log but don't block) |
| `maxInstructions` | 500000000 (0 = unlimited) |
| `timeoutMillis` | 0 (no limit) |
| `allow` | none. Wins over deny |
| `deny` | none. Added to the built-in list |

Entries use internal names: `java/net/Socket` (a class), `java/nio/file/` (a prefix), `java/lang/System.exit` (one member), `java/lang/System.*` (all members).

## Limits

- It only applies to the built-in emulator. The JavaVM-based decryptors (`zelix.string.*`, `dasho.string.*`, `allatori.string.*`) are not covered.
- The deny list can have gaps, and the time limit is only checked every few thousand instructions.
- It isn't a security boundary. Run untrusted samples in a VM or container with no network.

If a decryptor needs something blocked, run with `logOnly: true` to see what it asks for, then add a narrow `allow` entry.

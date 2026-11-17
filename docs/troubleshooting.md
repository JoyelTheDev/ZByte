# Troubleshooting

**`Could not locate a class file` (exit -2)**
A class is missing. Add the jar that has it to `path` or `libraries`.

**Stack overflow (exit -3)**
Run with `-Xss512m`, or `-Xss1g` for huge jars.

**`Could not locate specified transformer`**
Check the spelling and package prefix. See [Usage](usage.md#transformers-in-the-config).

**`X could not be found while writing Y`**
Harmless unless you need to run the output. Add the missing libraries to `path` to silence it.

**Decompiler can't open the output**
Some jars use ZIP tricks that crash tools. Run ZByte once with one cheap transformer and open that output. For malformed metadata, try `general.removers.IllegalSignatureRemover` and `universal.AccessRepairTransformer`.

**`IllegalArgumentException` while loading classes**
The jar may exploit ASM. Try `patchAsm: true`.

**Nothing changed**
Run `detect: true` on the input. Check transformer order, missing libraries, and any `Sandbox blocked` lines in the log.

**Never finishes**
Set `sandbox.timeoutMillis`, turn off `smartRedo`, or set `maxAnalysisInstructions` on the flow and number transformers.

**Later transformers stopped working after renaming**
Move normalizers to the end.

**`Deobfuscation failed` (exit -1)**
Open an issue with your config, the detection output, the full stack trace, and the sample if you may share it.

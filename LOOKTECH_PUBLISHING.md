# Looktech Publishing

This fork publishes Maven artifacts under the `ai.looktech` group instead of the upstream `dev.mobile` group.

Current coordinates use:

```text
groupId: ai.looktech
version: 2.6.0-looktech.1
repository: https://github.com/Entertech/Maestro
```

The upstream Maestro project remains Apache-2.0 licensed. Keep `LICENSE` intact, retain upstream attribution, and add clear notices for Entertech-modified files before redistributing source or binary artifacts.

Build and unsigned local publication smoke:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew \
  -PVERSION_NAME=2.6.0-looktech.1-SNAPSHOT \
  :maestro-ios:test \
  :maestro-cli:compileKotlin \
  publishToMavenLocal
```

The snapshot override is intentional for local smoke tests: the Maven publish plugin skips signing for `SNAPSHOT` versions. Release publication with `2.6.0-looktech.1` requires the same Central Portal user token and signing credentials used by the other Entertech `ai.looktech` libraries. Do not publish under upstream `dev.mobile` coordinates from this fork.

GitHub Actions release publishing uses `.github/workflows/publish-release.yaml`. The workflow uploads every Maestro module to Maven Central but does not automatically release the deployment; finish the release manually in Central Portal after the workflow succeeds.

Configure these repository variables and secrets, matching the existing `lt-vad` / `lt-rpc-*` publishing convention:

```text
Variable:
MAVEN_CENTRAL_USERNAME

Secrets:
MAVEN_CENTRAL_PASSWORD
SIGNING_IN_MEMORY_KEY
SIGNING_IN_MEMORY_KEY_ID
SIGNING_IN_MEMORY_KEY_PASSWORD
```

The workflow maps those values to the Gradle properties expected by the Maven publish plugin:

```text
mavenCentralUsername
mavenCentralPassword
signingInMemoryKey
signingInMemoryKeyPassword
signingInMemoryKeyId
```

Manual release upload from GitHub Actions:

```bash
gh workflow run publish-release.yaml -R Entertech/Maestro --ref main
```

To recover a single failed Central Portal component validation without re-uploading every module, pass the module name:

```bash
gh workflow run publish-release.yaml -R Entertech/Maestro --ref main -f module=maestro-cli
```

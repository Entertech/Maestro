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

The snapshot override is intentional for local smoke tests: the Maven publish plugin skips signing for `SNAPSHOT` versions. Release publication with `2.6.0-looktech.1` requires a verified `ai.looktech` namespace, signing credentials, and repository credentials. Do not publish under upstream `dev.mobile` coordinates from this fork.

GitHub Actions release publishing uses `.github/workflows/publish-release.yaml` and requires these repository secrets:

```text
ORG_GRADLE_PROJECT_MAVENCENTRALUSERNAME
ORG_GRADLE_PROJECT_MAVENCENTRALPASSWORD
SIGNING_PRIVATE_KEY
SIGNING_PASSWORD
SIGNING_KEY_ID
```

The workflow maps those secrets to the Gradle properties expected by the Maven publish plugin:

```text
mavenCentralUsername
mavenCentralPassword
signingInMemoryKey
signingInMemoryKeyPassword
signingInMemoryKeyId
```

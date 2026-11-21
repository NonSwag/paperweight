## paperweight

`paperweight` consists of three Gradle plugins:
- `paperweight-core`: Used to build Paper
- `paperweight-patcher`: Used to create forks of Paper or other `paperweight-patcher`-based forks
- `paperweight-userdev`: Used to develop internals plugins using Mojang mappings

### Building

paperweight builds with [jpm](https://github.com/java-package-manager/jpm), which installs the JDK and the Gradle
distribution the plugins compile against by itself:

```bash
curl -fsSL https://github.com/java-package-manager/jpm/releases/latest/download/install.sh | sh
jpm build    # the plugin jars, paperweight-core/dist/ and paperweight-userdev/dist/ (*-bundle.jar)
jpm test
```

`project.jsonc` in the root and in each module is the build; `project.lock` pins every dependency.

### How to use this for testing:

- Install `paperweight` to Maven Local:
```bash
jpm run publish file://$HOME/.m2/repository
```
- Add `mavenLocal()` for plugin resolution in your test project
  (see the [Gradle docs](https://docs.gradle.org/current/userguide/plugins.html#sec:custom_plugin_repositories) for more details)
- Adjust the `paperweight` version in your test project to the `version` in `project.jsonc`

> Most output `paperweight` creates goes into `<project-root>/.gradle/caches/paperweight`

### Debugging

Create a remote JVM debug run configuration in IntelliJ which connects to port 5005, then run Gradle in debug mode
in the project you test paperweight with:

```bash
./gradlew --no-daemon -Dorg.gradle.debug=true <task>
```

Gradle will not start until the debugger is connected so you don't need to worry about missing a breakpoint.

### Style Guide

This projects follows the opinionated [`ktlint`](https://ktlint.github.io/) linter and formatter, configured in
`.editorconfig`, and every source starts with the license header from `license/copyright.txt`.

```
jpm run lint      # check
jpm run format    # reformat with ktlint and fix the headers; adjust what ktlint can't fix itself
```

> This project uses many new Gradle features to make sure we're ready for Gradle 7.0 and beyond, and we don't find
> ourselves stuck in a bad position where it's too hard for us to update. That being said, Gradle always marks new APIs
> as unstable for a bit until the next major version, so you should probably disable the "Unstable API Usages" inspection
> in IntelliJ as well. The easiest way to do this is just find any place where an "unstable API" is used (tons in
> `Paperweight.kt`) and disable the inspection from there.

[![CI powered by namespace badge](https://papermc.io/assets/misc/namespace-oss-badge.svg?project=paperweight)](https://namespace.so/github-actions/?utm_source=oss&utm_campaign=papermc)

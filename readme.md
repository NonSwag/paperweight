## paperweight

`paperweight` consists of:
- `paperweight-core`: a Gradle plugin used to build Paper
- `paperweight-patcher`: a Gradle plugin used to create forks of Paper or other `paperweight-patcher`-based forks
- `jpm-paperweight`: a [jpm](https://github.com/java-package-manager/jpm) extension used to develop plugins against
  Paper's server internals

### Developing plugins against server internals

Install the extension once, then add the Paper server to a plugin project:

```bash
jpm add -g github:NonSwag/paperweight/jpm-paperweight
jpm paperweight init 26.3       # or a dev bundle version such as 26.3.build.257-stable, or latest
jpm build
```

`init` adds the Paper repository and `"dev": { "ext:paperweight/server": "<dev bundle version>" }` to
`project.jsonc`. When jpm resolves it, the extension downloads the dev bundle and Mojang's server, applies Paper's
patch (checking every checksum paperclip lists) and gives jpm the Mojang-mapped server jar to compile against,
with paper-api and the server's libraries as dependencies. Everything is cached in `~/.jpm/paperweight`. The
server is a `dev` dependency: the server provides it at run time. Projects need `"java": "25"` (or what the
Minecraft version requires).

It supports dev bundles of Minecraft 26.1 and newer (data version 8), which are not obfuscated, so plugins need no
remapping. Decompiled server sources for IDEs are not generated yet.

### Building

paperweight builds with [jpm](https://github.com/java-package-manager/jpm), which installs what it needs by itself,
the JDK included:

```bash
curl -fsSL https://github.com/java-package-manager/jpm/releases/latest/download/install.sh | sh
jpm build    # paperweight-core/dist/*-bundle.jar (the core and patcher plugins) and the jpm extension
jpm test
```

`project.jsonc` in the root and in each module is the build; `project.lock` pins every dependency.

### Testing the core and patcher plugins in a Gradle project

- Install `paperweight` to Maven Local:
```bash
jpm run publish file://$HOME/.m2/repository
```
- Add `mavenLocal()` for plugin resolution in your test project
  (see the [Gradle docs](https://docs.gradle.org/current/userguide/plugins.html#sec:custom_plugin_repositories) for more details)
- Adjust the `paperweight` version in your test project to the `version` in `project.jsonc`

> Most output `paperweight` creates goes into `<project-root>/.gradle/caches/paperweight`

### Debugging the Gradle plugins

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

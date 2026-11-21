# Paper with jpm

These manifests build [Paper](https://github.com/PaperMC/Paper) (Minecraft 26.3) with jpm and jpm-paperweight instead
of Gradle. Copy them into a Paper checkout (`project.jsonc` into its root, the others into `paper-api/` and
`paper-server/`), then:

```bash
jpm add -g github:NonSwag/paperweight/jpm-paperweight
jpm paperweight apply      # Minecraft's sources with Paper's patches, in paper-server/src/minecraft
jpm build                  # paper-api and paper-server
# change src/minecraft/java, commit (or fix up) in that repository, then:
jpm paperweight rebuild    # back into paper-server/patches
```

They were checked against Paper `d81ca50`: `apply` applies its 946 source, 3 resource and 35 feature patches,
`rebuild` right after reproduces every patch byte for byte, and `jpm build` compiles paper-server.

Not covered yet: the paperclip and bundler jars, the dev bundle, Paper's code generators and its
`log4jPlugins` source set (annotation processing for Log4j plugin metadata).

# Minecraft Source Bridge

DSH (DeepSeek Harness) bundle that gives the model exact access to
decompiled Minecraft/Forge 1.12.2 sources and to the rest of the project's
External Libraries (GeckoLib, Mixin, H2, Guava, Netty, ...), plus an optional
bridge to IntelliJ IDEA's MCP Server. A grass-block button next to Send turns
the whole bridge on or off per conversation.

Installed as `@local/mc-source` in the `desktop` profile.

## Components

| Server | Transport | Purpose |
| --- | --- | --- |
| `mc` | stdio (`server.js`) | Indexes the ForgeGradle `*-sources.jar`; class search, member outline, line-numbered reads, source grep. |
| `idea` | stdio (`idea-bridge.js`) → IDE `:64342/stream` | Curated IntelliJ tools for project code, builds, inspections, run configurations, and jar decompilation. |

MCP public tool names are `mcp__mc__*` and `mcp__idea__*`.

### `mc` tools

- `status` — jar path, Forge version, class/package counts.
- `search_classes` — find `net.minecraft.*` / `net.minecraftforge.*` classes.
- `class_outline` — fields, constructors, methods, nested types with line numbers.
- `read_class` — exact 1-based line range, with `next` continuation hint.
- `search_source` — literal or regex search with context.
- `list_packages` — package inventory with class counts.

Source jar:

```
C:/Users/ASUS/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/
  1.12.2-14.23.5.2854_mapped_snapshot_20171003-1.12/
  forge-1.12.2-14.23.5.2854_mapped_snapshot_20171003-1.12-sources.jar
```

2883 decompiled `net.minecraft` / `net.minecraftforge` classes (mappings
`snapshot_20171003-1.12`, Forge `14.23.5.2854`). The project's own sources are
not indexed; use the normal file tools for those.

### `idea` tools

Curated from the IDE's 62 tools (the rest are database/notebook/debugger noise):
`read_file`, `search_symbol`, `search_text`, `search_regex`, `search_file`,
`get_file_problems`, `build_project`, `get_project_modules`,
`get_run_configurations`, `execute_run_configuration`, `analyze_calls`,
`get_symbol_info`, plus `status`.

The bridge adds four library-aware tools on top:

- `search_classes` — symbol search across the project and **all External
  Libraries**; returns fully-qualified names and jar/source paths.
- `class_outline` — fields/constructors/methods/nested types with line numbers,
  including `.class`-only libraries.
- `read_class` — exact 1-based line range. If the library ships a sources jar it
  reads the `.java`; otherwise the bridge asks IntelliJ to decompile the
  `.class` and slices the requested range locally.
- `list_libraries` — dependency names from the IDE project model.

This is what makes GeckoLib, Mixin, H2, Guava, Netty, etc. readable with exact
line numbers instead of `read_file`'s head/tail preview.

`projectPath` is injected automatically (`IDEA_PROJECT_PATH`).

### External Libraries coverage

| Library | Source form | Access |
| --- | --- | --- |
| Minecraft + Forge 1.12.2 | ForgeGradle `-sources.jar` | `mc` tools, or `idea` read_class |
| GeckoLib 3.0.21 | `.class` only (deobf jar) | `idea` read_class decompiles |
| Mixin 0.8.3-SNAPSHOT | `-sources.jar` | `idea` read_class exact |
| H2 2.1.214 | `-sources.jar` | `idea` read_class exact |
| Guava / Netty / Gson / Log4j / LWJGL / ASM / ... | sources where IDEA resolves them | `idea` search_classes → read_class |
| JDK (`src.zip`) | `.java` | `idea` read_class exact |
| `libs/jfxrt.jar`, `libs/ime.jar` | `.class` only | `idea` read_class decompiles |

Use `mcp__idea__search_classes` to discover a class, `class_outline` to see its
members, then `read_class` with the member's line numbers.

## Composer toggle

The composer has a small grass-block button in `conversation.input.right`,
immediately before the Send action:

- **Off (dim)** — default. No source-bridge section is added to the system
  prompt, and `mcp__mc__*` / `mcp__idea__*` calls are denied by a tool guard.
- **On (lit green)** — the host injects a `## Minecraft source bridge: ON`
  section telling the model which tools to use, and the guard allows them.

The button executes `/mc on` or `/mc off` through the normal command channel.
A session projection (`mcSource`) derives the state from those durable
`command/run` / `command/done` events, so it survives resume and fork without a
private store. You can also type `/mc on` or `/mc off` directly.

If the button does not appear after installing the bundle, refresh the Web page
so the new Client module is loaded.

## IDEA setup

The JetBrains MCP Server plugin (`com.intellij.mcpServer`) is installed in
IntelliJ IDEA 2026.2 and was enabled through
`%APPDATA%\JetBrains\IntelliJIdea2026.2\options\mcpServer.xml`:

```xml
<application>
  <component name="McpServerSettings">
    <option name="enableMcpServer" value="true" />
    <option name="mcpServerPort" value="64342" />
  </component>
</application>
```

The IDE must be running for `mcp__idea__*` calls. If IDEA starts after the
harness, the bridge retries on the next call; call `mcp__idea__status` first.
The IDE exposes both `/sse` and `/stream`; this bundle uses the streamable HTTP
endpoint `/stream`.

Important: IntelliJ's `read_file` accepts archive paths, but only under project,
library, or SDK roots. Because `C:\Users\ASUS\.gradle` is a junction to
`D:\.gradle`, pass the dependency path as `D:/.gradle/...`, not
`C:/Users/ASUS/.gradle/...`. Paths returned by `search_classes` /
`search_symbol` already use the accepted form. For long files IntelliJ returns
a head/tail preview; use `read_class` (not `read_file`) for exact ranges.

## Configuration

`cordis.patch.yml` holds the two `@deepseek-ai/dsh-mcp-client` rows and the
`@local/mc-source` toggle row. Change `MC_SOURCES_JAR` there to point at a
different ForgeGradle sources jar.

After editing an installed bundle's patch, reinstall it:

1. `plugin_manager remove_bundle` target `@local/mc-source`
2. `plugin_manager install_bundle` target this directory

## Verification

- `mcp__mc__status` → should report 2883 classes.
- `mcp__mc__class_outline` on `net.minecraft.entity.EntityLivingBase` → 237
  members.
- `mcp__idea__status` → `connected: true`, `exposedTools: 16`.
- `mcp__idea__get_project_modules` → `mdk`, `mdk.main`, `mdk.test`.
- `mcp__idea__search_classes` on `AnimatedGeoModel` → GeckoLib jar path.
- `mcp__idea__class_outline` on `software.bernie.geckolib3.model.AnimatedGeoModel`
  → 12 members; `read_class` lines 36–60 returns decompiled Java.
- `mcp__idea__read_class` on `org.h2.Driver` lines 19–45 → `.java` from
  `h2-2.1.214-sources.jar`.
- `mcp__idea__read_class` on `org.spongepowered.asm.mixin.Mixin` lines 32–60 →
  `.java` from `mixin-0.8.3-SNAPSHOT-sources.jar`.
- Toggle off: `mcp__mc__status` returns the “source bridge is off” guard message
  and the system prompt has no source-bridge section.
- Toggle on: `mcp__mc__status` and `mcp__idea__status` succeed, and the system
  prompt includes `## Minecraft source bridge: ON`.

## Troubleshooting

- `mc` says the index is unavailable: check `MC_SOURCES_JAR` exists and rebuild
  the bundle.
- `idea` says unreachable: start IDEA, wait for indexing, then retry; the bridge
  reconnects lazily.
- IDEA rejects a jar path as "outside project, library, and SDK roots": use the
  `D:/.gradle/...` form of the same path.
- IDEA's MCP port changes if another IDE instance is running; update
  `IDEA_MCP_URL`.

## Files

- `index.js` — Host toggle: `mcSource` projection, `/mc` command, system-prompt
  section, and the tool guard.
- `client.js` — grass-block composer button and its locale/remote wiring.
- `server.js` — dependency-free ZIP reader + source index + MCP stdio server.
- `idea-bridge.js` — curated MCP proxy with automatic `projectPath`, plus
  library-aware `search_classes` / `class_outline` / `read_class` /
  `list_libraries` that use IDEA as the symbol index and decompiler.
- `idea-tools.json` — cached public tool schemas (refreshed on connect).
- `cordis.patch.yml`, `package.json`, `locale/*.json`, `icon.svg`.
- `dev/` — local test clients (not part of the packed package).

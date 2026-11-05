# Minecraft Source Bridge

DSH (DeepSeek Harness) bundle that gives the model exact access to the
decompiled Minecraft/Forge 1.12.2 sources used by this MDK, plus an optional
bridge to IntelliJ IDEA's MCP Server.

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

`projectPath` is injected automatically (`IDEA_PROJECT_PATH`).

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
`C:/Users/ASUS/.gradle/...`. Paths returned by `search_symbol` already use the
accepted form. For long files IntelliJ returns a head/tail preview, so use
`mcp__mc__*` or the harness file reader for precise long ranges.

## Configuration

`cordis.patch.yml` holds the two `@deepseek-ai/dsh-mcp-client` rows. Change
`MC_SOURCES_JAR` there to point at a different ForgeGradle sources jar.

After editing an installed bundle's patch, reinstall it:

1. `plugin_manager remove_bundle` target `@local/mc-source`
2. `plugin_manager install_bundle` target this directory

## Verification

- `mcp__mc__status` → should report 2883 classes.
- `mcp__mc__class_outline` on `net.minecraft.entity.EntityLivingBase` → 237
  members.
- `mcp__idea__status` → `connected: true`, `exposedTools: 12`.
- `mcp__idea__get_project_modules` → `mdk`, `mdk.main`, `mdk.test`.

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

- `server.js` — dependency-free ZIP reader + source index + MCP stdio server.
- `idea-bridge.js` — curated MCP proxy with automatic `projectPath`.
- `idea-tools.json` — cached public tool schemas (refreshed on connect).
- `cordis.patch.yml`, `package.json`, `locale/*.json`, `icon.svg`.
- `dev/` — local test clients (not part of the packed package).

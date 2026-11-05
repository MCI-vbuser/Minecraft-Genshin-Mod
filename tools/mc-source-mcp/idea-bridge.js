#!/usr/bin/env node
/**
 * idea-bridge
 *
 * A small MCP (stdio) bridge in front of IntelliJ IDEA's MCP Server.
 *
 * The IDE's server exposes 60+ tools and requires a `projectPath` argument on
 * every call, which is noisy for a coding model. This bridge:
 *   - keeps only the project/code-intelligence tools useful for mod work,
 *   - injects `projectPath` automatically,
 *   - caches the tool list so the harness still starts when IDEA is closed,
 *   - reconnects when the IDE restarts.
 */

import fs from 'node:fs';
import path from 'node:path';

const IDEA_URL = process.env.IDEA_MCP_URL ?? 'http://127.0.0.1:64342/stream';
const PROJECT_PATH = process.env.IDEA_PROJECT_PATH ?? 'C:/Users/ASUS/Desktop/mdk';
const CACHE_PATH = process.env.IDEA_TOOL_CACHE ?? path.join(import.meta.dirname, 'idea-tools.json');
const PROTOCOL_VERSION = '2025-06-18';
const CALL_TIMEOUT_MS = Number(process.env.IDEA_CALL_TIMEOUT_MS ?? 290_000);

const ALLOWED_TOOLS = [
  'read_file',
  'search_symbol',
  'search_text',
  'search_regex',
  'search_file',
  'get_file_problems',
  'build_project',
  'get_project_modules',
  'get_run_configurations',
  'execute_run_configuration',
  'analyze_calls',
  'get_symbol_info',
];

const INSTRUCTIONS = `IntelliJ IDEA bridge for the project at ${PROJECT_PATH}.

The IDE's own MCP tools are exposed here with a curated toolset; projectPath is always supplied automatically, so do not pass it.

Use these tools for:
- read_file: project files and jar entries. Paths inside the ForgeGradle dependency look like D:/.gradle/caches/forge_gradle/.../forge-...-sources.jar!/net/minecraft/.../Foo.java; the IDE can also decompile a .class entry in the -recomp.jar. For long files this returns a head/tail preview, so use it for a quick look rather than a long range.
- search_symbol / search_text / search_regex / search_file: project-wide navigation.
- get_file_problems / build_project: compile and inspection feedback after edits.
- get_run_configurations / execute_run_configuration: run the client or server.
- analyze_calls / get_symbol_info: call hierarchy and symbol details.

For Minecraft/Forge API questions, prefer the mc source-index tools: they are line-indexed and faster than driving the IDE. If an IDEA tool reports that the IDE is unreachable, start IDEA or continue with the mc tools.`;

function log(...parts) {
  process.stderr.write(`[idea-bridge] ${parts.join(' ')}\n`);
}

function stripProjectPath(tool) {
  const schema = structuredClone(tool.inputSchema ?? { type: 'object', properties: {} });
  if (schema.properties) delete schema.properties.projectPath;
  if (Array.isArray(schema.required)) schema.required = schema.required.filter((name) => name !== 'projectPath');
  let description = tool.description?.trim() ? `[IntelliJ IDEA] ${tool.description.trim()}` : `[IntelliJ IDEA] ${tool.name}`;
  if (tool.name === 'read_file') {
    description += '\n\nNote: for long files IntelliJ returns a head/tail preview rather than the full requested range. Use it for a quick look or a jar class; use the mc source tools or the harness file reader for precise long ranges.';
  }
  return {
    name: tool.name,
    description,
    inputSchema: schema,
  };
}

function readCache() {
  try {
    const parsed = JSON.parse(fs.readFileSync(CACHE_PATH, 'utf8'));
    if (Array.isArray(parsed.tools)) return parsed;
  } catch { /* no cache yet */ }
  return null;
}

function writeCache(tools) {
  try {
    fs.writeFileSync(CACHE_PATH, JSON.stringify({
      generatedAt: new Date().toISOString(),
      url: IDEA_URL,
      projectPath: PROJECT_PATH,
      tools,
    }, null, 2));
  } catch (error) {
    log(`cannot write tool cache: ${error.message}`);
  }
}

class IdeaClient {
  constructor() {
    this.sessionId = undefined;
    this.schemas = [];
    this.tools = [];
    this.ready = false;
    this.lastError = undefined;
    this.sessionStartedAt = undefined;
  }

  async connect(timeoutMs = 15_000) {
    this.ready = false;
    this.sessionId = undefined;
    const started = Date.now();
    const init = await this.post({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: {},
        clientInfo: { name: 'dsh-idea-bridge', version: '1.0.0' },
      },
    }, { timeoutMs, noSession: true });
    if (!init?.result) throw new Error('IDE did not return an initialize result');
    await this.post({ jsonrpc: '2.0', method: 'notifications/initialized' }, { timeoutMs, notification: true });
    const list = await this.post({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }, { timeoutMs });
    this.schemas = list?.result?.tools ?? [];
    this.tools = this.schemas.filter((tool) => ALLOWED_TOOLS.includes(tool.name)).map(stripProjectPath);
    if (this.tools.length === 0) throw new Error('IDE returned none of the expected tools');
    this.sessionStartedAt = new Date().toISOString();
    this.ready = true;
    this.lastError = undefined;
    writeCache(this.tools);
    log(`connected to ${IDEA_URL}; ${this.schemas.length} IDE tools, ${this.tools.length} exposed (${Date.now() - started}ms)`);
    return this.tools;
  }

  async post(body, { timeoutMs = 30_000, notification = false, noSession = false } = {}) {
    const headers = {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      origin: 'http://localhost',
    };
    if (!noSession && this.sessionId) {
      headers['mcp-session-id'] = this.sessionId;
      headers['mcp-protocol-version'] = PROTOCOL_VERSION;
    }
    const response = await fetch(IDEA_URL, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
    const session = response.headers.get('mcp-session-id');
    if (session) this.sessionId = session;
    if (notification) {
      await response.text();
      return undefined;
    }
    if (!response.ok) {
      const text = await response.text();
      const error = new Error(`IDEA HTTP ${response.status}: ${text.slice(0, 600)}`);
      error.status = response.status;
      throw error;
    }
    return this.readMessage(response, body.id, timeoutMs);
  }

  async readMessage(response, expectedId, timeoutMs = CALL_TIMEOUT_MS) {
    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.includes('text/event-stream')) {
      const message = await response.json();
      if (message?.id === expectedId) return message;
      if (message?.error) throw new Error(`IDEA JSON-RPC error: ${JSON.stringify(message.error)}`);
      return message;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let index;
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        const event = buffer.slice(0, index);
        buffer = buffer.slice(index + 2);
        const data = event
          .split('\n')
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trim())
          .join('\n');
        if (!data) continue;
        const message = JSON.parse(data);
        if (message?.id === expectedId) {
          await reader.cancel();
          if (message.error) throw new Error(`IDEA JSON-RPC error: ${JSON.stringify(message.error)}`);
          return message;
        }
        // A notification or progress event; keep reading for the response.
      }
      if (Date.now() > deadline) {
        await reader.cancel();
        throw new Error('timed out waiting for the IDEA response');
      }
    }
    throw new Error('IDEA closed the response stream without a result');
  }

  async call(toolName, args) {
    if (!this.ready) await this.connect();
    const schema = this.schemas.find((tool) => tool.name === toolName);
    const callArgs = { ...args };
    if (schema?.inputSchema?.properties?.projectPath !== undefined || !schema) {
      callArgs.projectPath = PROJECT_PATH;
    }
    const invoke = () => this.post({
      jsonrpc: '2.0',
      id: 3,
      method: 'tools/call',
      params: { name: toolName, arguments: callArgs },
    }, { timeoutMs: CALL_TIMEOUT_MS });
    try {
      return await invoke();
    } catch (error) {
      if (error.status === 404 || error.status === 400 || error.status === 401 || error.name === 'TypeError') {
        this.ready = false;
        this.sessionId = undefined;
        await this.connect();
        return invoke();
      }
      throw error;
    }
  }
}

const client = new IdeaClient();
const cached = readCache();
let cacheTools = cached?.tools ?? [];
try {
  await client.connect(5_000);
} catch (error) {
  client.lastError = error?.message ?? String(error);
  log(`IDE not available at startup: ${client.lastError}`);
}

function availableTools() {
  const tools = client.ready ? client.tools : cacheTools;
  return [
    ...tools,
    {
      name: 'status',
      description: 'Report whether the IntelliJ IDEA MCP server is reachable, which project path is used, and how many IDE tools are available.',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    },
  ];
}

function toolResult(text, isError = false) {
  return { content: [{ type: 'text', text }], isError };
}

function statusText() {
  return [
    `url: ${IDEA_URL}`,
    `projectPath: ${PROJECT_PATH}`,
    `connected: ${client.ready}`,
    `ideTools: ${client.schemas.length}`,
    `exposedTools: ${(client.ready ? client.tools : cacheTools).length}`,
    `sessionStartedAt: ${client.sessionStartedAt ?? '-'}`,
    `lastError: ${client.lastError ?? '-'}`,
    `cache: ${CACHE_PATH}`,
  ].join('\n');
}

function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

function sendResult(id, result) { send({ jsonrpc: '2.0', id, result }); }
function sendError(id, code, message) { send({ jsonrpc: '2.0', id, error: { code, message } }); }

function handle(message) {
  const { id, method, params } = message;
  if (method === 'initialize') {
    sendResult(id, {
      protocolVersion: typeof params?.protocolVersion === 'string' ? params.protocolVersion : PROTOCOL_VERSION,
      capabilities: { tools: { listChanged: false }, resources: { listChanged: false } },
      serverInfo: { name: 'idea-bridge', version: '1.0.0' },
      instructions: INSTRUCTIONS,
    });
    return;
  }
  if (method === 'ping') { sendResult(id, {}); return; }
  if (method === 'tools/list') { sendResult(id, { tools: availableTools() }); return; }
  if (method === 'resources/list') { sendResult(id, { resources: [] }); return; }
  if (method === 'resources/templates/list') { sendResult(id, { resourceTemplates: [] }); return; }
  if (method === 'prompts/list') { sendResult(id, { prompts: [] }); return; }
  if (method === 'tools/call') {
    const name = params?.name;
    const args = params?.arguments ?? {};
    if (name === 'status') { sendResult(id, toolResult(statusText())); return; }
    if (!ALLOWED_TOOLS.includes(name)) {
      sendResult(id, toolResult(`Unknown IDEA tool: ${name}`, true));
      return;
    }
    client.call(name, args)
      .then((response) => {
        if (response?.error) { sendResult(id, toolResult(`IDEA error: ${JSON.stringify(response.error)}`, true)); return; }
        const result = response?.result;
        if (result === undefined) { sendResult(id, toolResult('IDEA returned no result', true)); return; }
        // The harness projects text and image content; structured output is optional.
        sendResult(id, result);
      })
      .catch((error) => {
        client.lastError = error?.message ?? String(error);
        sendResult(id, toolResult(`IDEA bridge call failed: ${client.lastError}`, true));
      });
    return;
  }
  sendError(id, -32601, `method not found: ${method}`);
}

let buffer = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buffer += chunk;
  let index;
  while ((index = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    if (!line) continue;
    let message;
    try {
      message = JSON.parse(line);
    } catch (error) {
      log(`invalid JSON: ${error.message}`);
      sendError(null, -32700, 'parse error');
      continue;
    }
    if (message && typeof message === 'object' && !Array.isArray(message)) {
      if (message.id === undefined || message.id === null) continue;
      handle(message);
    }
  }
});
process.stdin.on('end', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
process.on('SIGINT', () => process.exit(0));
log(`ready; ${client.ready ? 'connected' : 'using cached tools'}`);

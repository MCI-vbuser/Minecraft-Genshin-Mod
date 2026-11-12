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

External Libraries (Minecraft/Forge, GeckoLib, Mixin, H2, Guava, Netty, ...) are covered by these bridge tools:
- search_classes: find any class, method, or field in the project or its libraries.
- class_outline: fields, constructors, methods, and nested types with line numbers, including .class-only libraries.
- read_class: exact 1-based line ranges. Reads .java from a sources jar when present; otherwise IntelliJ decompiles the .class file. This is the right tool for reading GeckoLib/Mixin/H2/etc.; do not use read_file for long ranges.
- list_libraries: dependency names reported by the IDE.

Other IDE tools:
- read_file: quick look at a file or jar entry. For long files IntelliJ returns a head/tail preview, so prefer read_file only for a peek.
- search_symbol / search_text / search_regex / search_file: project-wide navigation.
- get_file_problems / build_project: compile and inspection feedback after edits.
- get_run_configurations / execute_run_configuration: run the client or server.
- analyze_calls / get_symbol_info: call hierarchy and symbol details.

For Minecraft/Forge API questions, the mc source-index tools are faster and already line-indexed; use the bridge for other libraries and project code. If an IDEA tool reports that the IDE is unreachable, start IDEA or continue with the mc tools.`;

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

/* ------------------------------------------------------------------ */
/* External-library class tools                                       */
/*                                                                    */
/* These use IDEA only as a symbol index and decompiler. The bridge    */
/* fetches the full text (limit 5000) and slices exact ranges itself,  */
/* so .java sources and Fernflower-decompiled .class entries behave    */
/* the same as ordinary files.                                         */
/* ------------------------------------------------------------------ */

const CUSTOM_TOOLS = [
  {
    name: 'search_classes',
    description: 'Search classes, methods, and fields across the project and every External Library (Minecraft/Forge, GeckoLib, Mixin, H2, Guava, Netty, ...) using IntelliJ\'s symbol index. Returns fully-qualified names and jar/source paths. Follow with class_outline or read_class.',
    inputSchema: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Class, method, or field name fragment, e.g. "AnimatedGeoModel", "org.spongepowered.asm.mixin.Mixin", or "setLivingAnimations".' },
        limit: { type: 'number', description: 'Maximum results to return (default 20, max 50).' },
        includeExternal: { type: 'boolean', description: 'Include SDK and library symbols (default true).' },
      },
      required: ['query'],
      additionalProperties: false,
    },
  },
  {
    name: 'class_outline',
    description: 'List fields, constructors, methods, and nested types with 1-based line numbers for any class in the project or an External Library, including classes that only exist as .class files. Use it before reading a large class such as AnimatedGeoModel or MixinApplicatorStandard.',
    inputSchema: {
      type: 'object',
      properties: {
        class: { type: 'string', description: 'Fully-qualified class name, or a unique simple name, e.g. "software.bernie.geckolib3.model.AnimatedGeoModel".' },
        maxMembers: { type: 'number', description: 'Maximum members to list (default 400, max 2000).' },
      },
      required: ['class'],
      additionalProperties: false,
    },
  },
  {
    name: 'read_class',
    description: 'Read an exact 1-based line range from any class in the project or an External Library. Reads .java from a sources jar when available; otherwise asks IntelliJ to decompile the .class file. Unlike read_file, this returns the requested range instead of a head/tail preview.',
    inputSchema: {
      type: 'object',
      properties: {
        class: { type: 'string', description: 'Fully-qualified class name (preferred) or a unique simple name.' },
        startLine: { type: 'number', description: 'First line to return (1-based, default 1).' },
        endLine: { type: 'number', description: 'Last line to return (inclusive). Defaults to startLine + 249, capped at 800 lines per call.' },
      },
      required: ['class'],
      additionalProperties: false,
    },
  },
  {
    name: 'list_libraries',
    description: 'List the dependency names IntelliJ reports for this project (the External Libraries set). Use it to see which libraries are available, then search_classes / class_outline / read_class to inspect their classes.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
];

const CUSTOM_TOOL_NAMES = new Set(CUSTOM_TOOLS.map((tool) => tool.name));
const CLASS_TEXT_CACHE = new Map();
const CLASS_TEXT_PENDING = new Map();
const MAX_CACHED_CLASSES = 16;

function toolText(result) {
  const payload = result?.result ?? result;
  const content = payload?.content;
  if (!Array.isArray(content)) return undefined;
  return content.find((item) => item?.type === 'text' && typeof item.text === 'string')?.text;
}

function parseToolJson(result) {
  const text = toolText(result);
  if (typeof text !== 'string') return undefined;
  try { return JSON.parse(text); } catch { return undefined; }
}

function pathInfo(filePath) {
  const normalized = String(filePath ?? '').replaceAll('\\', '/');
  const marker = normalized.indexOf('!/');
  if (marker < 0) {
    return { path: normalized, jar: undefined, entry: undefined, fqn: undefined, library: undefined };
  }
  const jar = normalized.slice(0, marker);
  const entry = normalized.slice(marker + 2);
  const fqn = entry.replace(/\.(java|class)$/i, '').replaceAll('/', '.');
  const library = path.posix.basename(jar).replace(/\.jar$/i, '');
  return { path: normalized, jar, entry, fqn, library };
}

async function resolveClassPath(input) {
  const raw = String(input ?? '').trim();
  if (raw.includes('!/')) return pathInfo(raw);
  const simple = raw.slice(raw.lastIndexOf('.') + 1);
  const response = await client.call('search_symbol', { q: raw, include_external: true, limit: 30 });
  const data = parseToolJson(response);
  const items = Array.isArray(data?.items) ? data.items : [];
  const candidates = [];
  for (const item of items) {
    if (!item?.filePath) continue;
    const info = pathInfo(item.filePath);
    if (!info.entry) continue;
    const base = info.entry.slice(info.entry.lastIndexOf('/') + 1).replace(/\.(java|class)$/i, '');
    if (base !== simple && info.fqn !== raw) continue;
    candidates.push({ ...info, startLine: item.startLine, endLine: item.endLine });
  }
  const exact = candidates.find((candidate) => candidate.fqn === raw)
    ?? candidates.find((candidate) => candidate.fqn?.endsWith(`.${raw}`));
  if (exact) return exact;
  if (candidates.length === 1) return candidates[0];
  if (candidates.length === 0) {
    return { error: `Class "${raw}" was not found in the project or its libraries. Try search_classes with a shorter fragment.` };
  }
  const names = candidates.slice(0, 25).map((candidate) => candidate.fqn).join('\n');
  return { error: `Class "${raw}" is ambiguous. Re-run with a fully-qualified name:\n${names}` };
}

function parseNumbered(text) {
  const map = new Map();
  let total = 0;
  for (const line of String(text).split('\n')) {
    const match = /^L(\d+): ?(.*)$/.exec(line);
    if (!match) continue;
    const number = Number(match[1]);
    map.set(number, match[2]);
    if (number > total) total = number;
  }
  return { map, total };
}

async function fetchClassText(info) {
  const key = info.path;
  const cached = CLASS_TEXT_CACHE.get(key);
  if (cached) return cached;
  if (CLASS_TEXT_PENDING.has(key)) return CLASS_TEXT_PENDING.get(key);

  const promise = (async () => {
    const collected = new Map();
    let total = 0;
    let offset = 1;
    for (let attempt = 0; attempt < 10; attempt += 1) {
      const response = await client.call('read_file', { file_path: key, offset, limit: 5000 });
      const text = toolText(response);
      if (typeof text !== 'string') throw new Error(`IntelliJ returned no text for ${key}`);
      const parsed = parseNumbered(text);
      if (parsed.total === 0) throw new Error(`IntelliJ returned no numbered lines for ${key}: ${text.slice(0, 300)}`);
      for (const [number, line] of parsed.map) collected.set(number, line);
      if (parsed.total > total) total = parsed.total;
      let missing = 0;
      for (let number = 1; number <= total; number += 1) {
        if (!collected.has(number)) { missing = number; break; }
      }
      if (missing === 0) break;
      offset = missing;
    }
    const lines = [];
    for (let number = 1; number <= total; number += 1) lines.push(collected.get(number) ?? '');
    const value = { lines, total, path: key };
    CLASS_TEXT_CACHE.set(key, value);
    if (CLASS_TEXT_CACHE.size > MAX_CACHED_CLASSES) {
      CLASS_TEXT_CACHE.delete(CLASS_TEXT_CACHE.keys().next().value);
    }
    return value;
  })();

  CLASS_TEXT_PENDING.set(key, promise);
  try {
    return await promise;
  } finally {
    CLASS_TEXT_PENDING.delete(key);
  }
}

function maskJava(source) {
  const out = source.split('');
  let state = 0; // 0 code, 1 line comment, 2 block comment, 3 string, 4 char
  for (let i = 0; i < source.length; i += 1) {
    const char = source[i];
    const next = source[i + 1];
    if (state === 0) {
      if (char === '/' && next === '/') { out[i] = ' '; out[i + 1] = ' '; i += 1; state = 1; }
      else if (char === '/' && next === '*') { out[i] = ' '; out[i + 1] = ' '; i += 1; state = 2; }
      else if (char === '"') { out[i] = ' '; state = 3; }
      else if (char === "'") { out[i] = ' '; state = 4; }
    } else if (state === 1) {
      if (char === '\n') state = 0; else out[i] = ' ';
    } else if (state === 2) {
      if (char === '*' && next === '/') { out[i] = ' '; out[i + 1] = ' '; i += 1; state = 0; }
      else if (char !== '\n') out[i] = ' ';
    } else {
      const quote = state === 3 ? '"' : "'";
      if (char === '\\') { out[i] = ' '; if (next !== undefined) { out[i + 1] = ' '; i += 1; } }
      else if (char === quote) { out[i] = ' '; state = 0; }
      else if (char !== '\n') out[i] = ' ';
    }
  }
  return out.join('');
}

function outlineJava(text, simpleName) {
  const rawLines = text.split('\n');
  const maskedLines = maskJava(text).split('\n');
  let declarationLine = -1;
  for (let i = 0; i < Math.min(maskedLines.length, 800); i += 1) {
    if (/\b(class|interface|enum)\s+\w+/.test(maskedLines[i]) && !/\bnew\s+\w+/.test(maskedLines[i])) {
      declarationLine = i;
      break;
    }
  }
  if (declarationLine < 0) return { header: `class ${simpleName}`, members: [], totalLines: rawLines.length };

  let depth = 0;
  let bodyStart = -1;
  for (let i = declarationLine; i < maskedLines.length; i += 1) {
    for (const char of maskedLines[i]) {
      if (char === '{') { depth += 1; if (bodyStart < 0) bodyStart = i; }
      else if (char === '}') depth -= 1;
    }
    if (bodyStart >= 0 && depth > 0) break;
  }
  if (bodyStart < 0) return { header: `class ${simpleName}`, members: [], totalLines: rawLines.length };

  const header = rawLines.slice(declarationLine, bodyStart + 1).join(' ')
    .replace(/\s+/g, ' ').replace(/\{.*$/, '').trim();
  const members = [];
  let i = bodyStart + 1;
  depth = 1;
  while (i < rawLines.length) {
    const trimmed = maskedLines[i].trim();
    if (depth === 1) {
      if (trimmed === '') { i += 1; continue; }
      if (trimmed.startsWith('}')) break;
      if (trimmed.startsWith('@')) { i += 1; continue; }
    } else {
      i += 1;
      continue;
    }

    const signatureLines = [];
    let d = depth;
    let terminator = null;
    let j = i;
    for (; j < rawLines.length; j += 1) {
      signatureLines.push(rawLines[j]);
      const masked = maskedLines[j];
      for (let k = 0; k < masked.length; k += 1) {
        const char = masked[k];
        if (char === '{') {
          d += 1;
          if (d === 2 && terminator === null) terminator = '{';
        } else if (char === '}') {
          d -= 1;
          if (d === 0) break;
          if (d === 1 && terminator === null) terminator = '}';
        } else if (char === ';' && d === 1 && terminator === null) {
          terminator = ';';
        }
      }
      if (terminator !== null) {
        if (terminator === '{' && d > 1) {
          // keep scanning to skip the body
        } else {
          j += 1;
          break;
        }
      }
      if (d === 1 && terminator === null && j > i) break;
    }

    const joined = signatureLines.join('\n');
    const cutIndex = terminator === ';' ? joined.indexOf(';') : joined.indexOf('{');
    let signature = (cutIndex >= 0 ? joined.slice(0, cutIndex) : joined).replace(/\s+/g, ' ').trim();
    if (signature.length > 320) signature = `${signature.slice(0, 317)}...`;
    const firstEquals = signature.indexOf('=');
    const firstParen = signature.indexOf('(');
    const isFieldInitializer = firstEquals >= 0 && (firstParen < 0 || firstEquals < firstParen);
    let kind = 'field';
    if (/^static\s*\{\s*$/.test(signature) || /^\{\s*$/.test(signature)) kind = 'initializer';
    else if (/\b(class|interface|enum)\s+\w+/.test(signature)) kind = 'nested type';
    else if (isFieldInitializer) kind = 'field';
    else if (signature.includes('(')) {
      const nameMatch = signature.match(/([A-Za-z_$][\w$]*)\s*\(/g);
      const isConstructor = nameMatch?.some((piece) => piece.slice(0, -1).trim() === simpleName);
      kind = isConstructor && !/\b(class|interface|enum)\b/.test(signature) ? 'constructor' : 'method';
    }
    if (signature && kind === 'initializer') signature = signature || 'initializer';
    if (signature) members.push({ kind, line: i + 1, signature });
    i = j;
    depth = 1;
  }
  return { header, members, totalLines: rawLines.length };
}

function numberedLines(lines, startLine, endLine) {
  const width = String(endLine).length;
  const output = [];
  for (let line = startLine; line <= endLine; line += 1) {
    output.push(`L${String(line).padStart(width, ' ')}: ${lines[line - 1] ?? ''}`);
  }
  return output.join('\n');
}

async function runCustomTool(name, args) {
  if (name === 'list_libraries') {
    const response = await client.call('get_project_dependencies', {});
    const data = parseToolJson(response);
    const deps = Array.isArray(data?.dependencies) ? data.dependencies : [];
    if (deps.length === 0) return toolResult('IntelliJ returned no dependencies for this project.');
    const lines = deps.map((dep) => `${dep.name}${dep.version ? ` (${dep.version})` : ''}  [${dep.source ?? 'unknown'}]`);
    return toolResult(`${lines.join('\n')}\n\n[${deps.length} dependencies reported by IntelliJ]`);
  }

  if (name === 'search_classes') {
    const query = String(args.query ?? '').trim();
    if (!query) throw new Error('query is required');
    const limit = Math.max(1, Math.min(Number(args.limit) || 20, 50));
    const response = await client.call('search_symbol', {
      q: query,
      include_external: args.includeExternal !== false,
      limit,
      projectPath: PROJECT_PATH,
    });
    const data = parseToolJson(response);
    const items = Array.isArray(data?.items) ? data.items : [];
    if (items.length === 0) return toolResult(`No symbol matched "${query}". Try a shorter fragment or list_libraries.`);
    const seen = new Set();
    const lines = [];
    for (const item of items) {
      if (!item?.filePath) continue;
      const info = pathInfo(item.filePath);
      if (!info.entry || seen.has(info.path)) continue;
      seen.add(info.path);
      lines.push(`${info.fqn}  [${info.library}]  ${info.path}`);
      if (lines.length >= limit) break;
    }
    return toolResult(`${lines.join('\n')}\n\n[${lines.length} class path(s); use class_outline or read_class next]`);
  }

  if (name === 'class_outline') {
    const info = await resolveClassPath(args.class);
    if (!info.path) return toolResult(info.error ?? 'Class not found', true);
    const { lines, total } = await fetchClassText(info);
    const outline = outlineJava(lines.join('\n'), info.fqn?.split('.').pop() ?? '');
    const maxMembers = Math.max(10, Math.min(Number(args.maxMembers) || 400, 2000));
    const groups = new Map();
    for (const member of outline.members) {
      const bucket = groups.get(member.kind) ?? [];
      bucket.push(member);
      groups.set(member.kind, bucket);
    }
    const parts = [
      `class: ${info.fqn ?? info.path}`,
      `source: ${info.path}`,
      `lines: ${total}`,
      `declaration: ${outline.header}`,
      `members: ${outline.members.length}`,
    ];
    let count = 0;
    for (const [kind, members] of groups) {
      parts.push('', `${kind}s (${members.length}):`);
      for (const member of members) {
        if (count >= maxMembers) {
          parts.push(`[stopped after ${maxMembers} members; raise maxMembers or read a range directly]`);
          return toolResult(parts.join('\n'));
        }
        parts.push(`  L${member.line}: ${member.signature}`);
        count += 1;
      }
    }
    return toolResult(parts.join('\n'));
  }

  if (name === 'read_class') {
    const info = await resolveClassPath(args.class);
    if (!info.path) return toolResult(info.error ?? 'Class not found', true);
    const { lines, total } = await fetchClassText(info);
    let start = Math.max(1, Math.floor(Number(args.startLine) || 1));
    if (start > total) start = Math.max(1, total - 199);
    const requestedEnd = Number(args.endLine);
    let end = Number.isFinite(requestedEnd) && requestedEnd > 0 ? Math.floor(requestedEnd) : start + 249;
    end = Math.min(end, start + 799, total);
    if (end < start) end = Math.min(start + 249, total);
    const kind = info.entry?.endsWith('.class') ? 'decompiled class' : 'source';
    const header = `// ${info.fqn ?? path.basename(info.path)} | ${info.path} | lines ${start}-${end} of ${total} (${kind})`;
    const footer = end < total ? `\n[next: read_class startLine=${end + 1} endLine=${Math.min(end + 250, total)}]` : '\n[end of file]';
    return toolResult(`${header}\n${numberedLines(lines, start, end)}${footer}`);
  }

  throw new Error(`Unknown custom tool: ${name}`);
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
    this.nextId = 10;
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
    const requestId = this.nextId++;
    const invoke = () => this.post({
      jsonrpc: '2.0',
      id: requestId,
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
    ...CUSTOM_TOOLS,
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
    `exposedTools: ${(client.ready ? client.tools : cacheTools).length + CUSTOM_TOOLS.length}`,
    `customTools: ${CUSTOM_TOOLS.length}`,
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
      instructions: '',
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
    if (CUSTOM_TOOL_NAMES.has(name)) {
      runCustomTool(name, args)
        .then((result) => sendResult(id, result))
        .catch((error) => {
          client.lastError = error?.message ?? String(error);
          sendResult(id, toolResult(`${name} failed: ${client.lastError}`, true));
        });
      return;
    }
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

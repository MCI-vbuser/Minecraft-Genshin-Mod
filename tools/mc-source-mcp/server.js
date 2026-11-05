#!/usr/bin/env node
/**
 * mc-source-mcp
 *
 * A dependency-free MCP (stdio) server that indexes a ForgeGradle
 * decompiled-sources jar (Minecraft + Forge) and exposes line-accurate
 * search / outline / read tools to the model.
 *
 * It exists because guessing Minecraft 1.12.2 / Forge APIs from memory is
 * unreliable. Every answer about vanilla or Forge behaviour should be backed
 * by a line read through these tools.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

const SERVER_NAME = 'mc-source';
const SERVER_VERSION = '1.0.0';
const DEFAULT_MAX_TEXT = 120_000;
const MCP_PROTOCOL_FALLBACK = '2025-06-18';

function log(...parts) {
  process.stderr.write(`[mc-source] ${parts.join(' ')}\n`);
}

function argValue(flag) {
  const index = process.argv.indexOf(flag);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

/* ------------------------------------------------------------------ */
/* Jar discovery                                                       */
/* ------------------------------------------------------------------ */

function candidateSourceJars() {
  const candidates = [];
  const explicit = process.env.MC_SOURCES_JAR || argValue('--jar');
  if (explicit) candidates.push(explicit);
  if (process.env.MC_SOURCES_JARS) {
    for (const item of process.env.MC_SOURCES_JARS.split(path.delimiter)) {
      if (item.trim()) candidates.push(item.trim());
    }
  }
  const home = os.homedir();
  const forgeGradle = path.join(home, '.gradle', 'caches', 'forge_gradle', 'minecraft_user_repo', 'net', 'minecraftforge', 'forge');
  try {
    for (const dir of fs.readdirSync(forgeGradle)) {
      const full = path.join(forgeGradle, dir);
      let names = [];
      try { names = fs.readdirSync(full); } catch { continue; }
      for (const name of names) {
        if (name.endsWith('-sources.jar') && name.includes('mapped')) {
          candidates.push(path.join(full, name));
        }
      }
    }
  } catch { /* cache absent */ }
  return candidates.filter((file) => {
    try { return fs.statSync(file).isFile(); } catch { return false; }
  });
}

function chooseSourceJar() {
  const explicit = process.env.MC_SOURCES_JAR || argValue('--jar');
  if (explicit) return { file: explicit, explicit: true };
  const candidates = candidateSourceJars();
  if (candidates.length === 0) return { file: null, explicit: false };
  candidates.sort((a, b) => {
    const sa = safeStat(a);
    const sb = safeStat(b);
    return (sb?.mtimeMs ?? 0) - (sa?.mtimeMs ?? 0) || a.localeCompare(b);
  });
  return { file: candidates[0], explicit: false };
}

function safeStat(file) {
  try { return fs.statSync(file); } catch { return null; }
}

/* ------------------------------------------------------------------ */
/* Minimal ZIP reader (no dependencies)                                */
/* ------------------------------------------------------------------ */

function parseZipDirectory(file) {
  const fd = fs.openSync(file, 'r');
  try {
    const stat = fs.fstatSync(fd);
    const tailSize = Math.min(stat.size, 65_557 + 1024);
    if (tailSize < 22) throw new Error('file too small to be a zip');
    const tail = Buffer.alloc(tailSize);
    fs.readSync(fd, tail, 0, tailSize, stat.size - tailSize);
    let eocd = -1;
    for (let i = tail.length - 22; i >= 0; i -= 1) {
      if (tail.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('zip end-of-central-directory record not found');
    const total = tail.readUInt16LE(eocd + 10);
    const cdSize = tail.readUInt32LE(eocd + 12);
    const cdOffset = tail.readUInt32LE(eocd + 16);
    if (total === 0xffff || cdSize === 0xffffffff || cdOffset === 0xffffffff) {
      throw new Error('ZIP64 archives are not supported');
    }
    const cd = Buffer.alloc(cdSize);
    fs.readSync(fd, cd, 0, cdSize, cdOffset);
    const entries = [];
    let pos = 0;
    for (let n = 0; n < total; n += 1) {
      if (pos + 46 > cd.length || cd.readUInt32LE(pos) !== 0x02014b50) {
        throw new Error(`corrupt central directory at entry ${n}`);
      }
      const method = cd.readUInt16LE(pos + 10);
      const compressedSize = cd.readUInt32LE(pos + 20);
      const uncompressedSize = cd.readUInt32LE(pos + 24);
      const nameLength = cd.readUInt16LE(pos + 28);
      const extraLength = cd.readUInt16LE(pos + 30);
      const commentLength = cd.readUInt16LE(pos + 32);
      const localOffset = cd.readUInt32LE(pos + 42);
      const name = cd.toString('utf8', pos + 46, pos + 46 + nameLength);
      entries.push({ name, method, compressedSize, uncompressedSize, localOffset });
      pos += 46 + nameLength + extraLength + commentLength;
    }
    return { fd, entries, size: stat.size };
  } catch (error) {
    try { fs.closeSync(fd); } catch { /* ignore */ }
    throw error;
  }
}

function readZipEntry(zip, entry) {
  const header = Buffer.alloc(30);
  fs.readSync(zip.fd, header, 0, 30, entry.localOffset);
  if (header.readUInt32LE(0) !== 0x04034b50) throw new Error(`bad local header for ${entry.name}`);
  const nameLength = header.readUInt16LE(26);
  const extraLength = header.readUInt16LE(28);
  const dataOffset = entry.localOffset + 30 + nameLength + extraLength;
  const data = Buffer.alloc(entry.compressedSize);
  fs.readSync(zip.fd, data, 0, entry.compressedSize, dataOffset);
  let raw;
  if (entry.method === 0) raw = data;
  else if (entry.method === 8) raw = zlib.inflateRawSync(data);
  else throw new Error(`unsupported zip compression method ${entry.method} for ${entry.name}`);
  if (entry.uncompressedSize > 0 && raw.length !== entry.uncompressedSize) {
    throw new Error(`size mismatch for ${entry.name}`);
  }
  return raw;
}

/* ------------------------------------------------------------------ */
/* Source index                                                        */
/* ------------------------------------------------------------------ */

class SourceIndex {
  constructor(jarPath) {
    this.jarPath = jarPath;
    this.version = path.basename(jarPath).replace(/-sources\.jar$/, '');
    this.zip = parseZipDirectory(jarPath);
    this.byFqn = new Map();
    this.bySimple = new Map();
    this.packages = new Map();
    this.textCache = new Map();
    this.startedAt = Date.now();
    for (const entry of this.zip.entries) {
      if (!entry.name.endsWith('.java')) continue;
      if (!entry.name.startsWith('net/minecraft/') && !entry.name.startsWith('net/minecraftforge/')) continue;
      const fqn = entry.name.slice(0, -'.java'.length).replaceAll('/', '.');
      const simple = fqn.slice(fqn.lastIndexOf('.') + 1);
      const meta = { fqn, simple, entry: entry.name, size: entry.uncompressedSize };
      this.byFqn.set(fqn, meta);
      const bucket = this.bySimple.get(simple.toLowerCase()) ?? [];
      bucket.push(meta);
      this.bySimple.set(simple.toLowerCase(), bucket);
      const pkg = fqn.slice(0, fqn.lastIndexOf('.'));
      this.packages.set(pkg, (this.packages.get(pkg) ?? 0) + 1);
    }
    this.indexedAt = new Date().toISOString();
    log(`indexed ${this.byFqn.size} classes from ${jarPath}`);
  }

  text(meta) {
    const cached = this.textCache.get(meta.entry);
    if (cached !== undefined) return cached;
    const raw = readZipEntry(this.zip, this.zip.entries.find((entry) => entry.name === meta.entry));
    const text = raw.toString('utf8').replaceAll('\r\n', '\n').replaceAll('\r', '\n');
    this.textCache.set(meta.entry, text);
    return text;
  }

  resolve(name) {
    let normalized = String(name ?? '').trim().replaceAll('\\', '/').replace(/\.java$/i, '');
    if (normalized.startsWith('jar://')) normalized = normalized.replace(/^jar:\/\//, '');
    if (normalized.includes('!/')) normalized = normalized.split('!/').pop();
    normalized = normalized.replaceAll('/', '.');
    if (this.byFqn.has(normalized)) return { meta: this.byFqn.get(normalized) };

    const simple = normalized.slice(normalized.lastIndexOf('.') + 1);
    let candidates = this.bySimple.get(simple.toLowerCase()) ?? [];
    if (normalized.includes('.')) {
      candidates = candidates.filter((meta) => meta.fqn === normalized || meta.fqn.endsWith(`.${normalized}`));
    }
    if (candidates.length === 1) return { meta: candidates[0] };
    return { candidates: candidates.slice(0, 25), total: candidates.length };
  }

  outline(meta) {
    const text = this.text(meta);
    const rawLines = text.split('\n');
    const maskedLines = maskJava(text).split('\n');
    let declarationLine = -1;
    for (let i = 0; i < Math.min(maskedLines.length, 400); i += 1) {
      if (/\b(class|interface|enum)\s+\w+/.test(maskedLines[i]) && !/\bnew\s+\w+/.test(maskedLines[i])) {
        declarationLine = i;
        break;
      }
    }
    if (declarationLine < 0) return { header: `class ${meta.simple}`, members: [], totalLines: rawLines.length };

    let depth = 0;
    let bodyStart = -1;
    for (let i = declarationLine; i < maskedLines.length; i += 1) {
      for (const char of maskedLines[i]) {
        if (char === '{') { depth += 1; if (bodyStart < 0) bodyStart = i; }
        else if (char === '}') depth -= 1;
      }
      if (bodyStart >= 0 && depth > 0) break;
    }
    if (bodyStart < 0) return { header: `class ${meta.simple}`, members: [], totalLines: rawLines.length };

    const header = rawLines.slice(declarationLine, bodyStart + 1).join(' ').replace(/\s+/g, ' ').replace(/\{.*$/, '').trim();
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
      const beforeBrace = signature;
      const firstEquals = beforeBrace.indexOf('=');
      const firstParen = beforeBrace.indexOf('(');
      // A top-level `=` before the first `(` identifies a field initializer,
      // even when the initializer itself calls a method (e.g. LogManager.getLogger()).
      const isFieldInitializer = firstEquals >= 0 && (firstParen < 0 || firstEquals < firstParen);
      let kind = 'field';
      if (/^static\s*\{\s*$/.test(beforeBrace) || /^\{\s*$/.test(beforeBrace)) kind = 'initializer';
      else if (/\b(class|interface|enum)\s+\w+/.test(beforeBrace)) kind = 'nested type';
      else if (isFieldInitializer) kind = 'field';
      else if (beforeBrace.includes('(')) {
        const nameMatch = beforeBrace.match(/([A-Za-z_$][\w$]*)\s*\(/g);
        const isConstructor = nameMatch?.some((piece) => piece.slice(0, -1).trim() === meta.simple);
        kind = isConstructor && !/\b(class|interface|enum)\b/.test(beforeBrace) ? 'constructor' : 'method';
      }
      if (signature && kind !== 'initializer') {
        members.push({ kind, line: i + 1, signature });
      } else if (kind === 'initializer') {
        members.push({ kind, line: i + 1, signature: beforeBrace || 'initializer' });
      }
      i = j;
      depth = 1;
    }
    return { header, members, totalLines: rawLines.length };
  }
}

/* ------------------------------------------------------------------ */
/* Java masking + outline parsing                                      */
/* ------------------------------------------------------------------ */

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
    } else if (state === 3 || state === 4) {
      const quote = state === 3 ? '"' : "'";
      if (char === '\\') { out[i] = ' '; if (next !== undefined) { out[i + 1] = ' '; i += 1; } }
      else if (char === quote) { out[i] = ' '; state = 0; }
      else if (char !== '\n') out[i] = ' ';
    }
  }
  return out.join('');
}

/* ------------------------------------------------------------------ */
/* Formatting helpers                                                  */
/* ------------------------------------------------------------------ */

function numberedLines(lines, startLine, endLine) {
  const width = String(endLine).length;
  const output = [];
  for (let line = startLine; line <= endLine; line += 1) {
    output.push(`${String(line).padStart(width, ' ')}: ${lines[line - 1] ?? ''}`);
  }
  return output.join('\n');
}

function textResult(text, isError = false) {
  return { content: [{ type: 'text', text }], isError };
}

function clip(text, limit = DEFAULT_MAX_TEXT) {
  if (text.length <= limit) return text;
  return `${text.slice(0, limit)}\n\n[truncated: output exceeded ${limit} characters]`;
}

/* ------------------------------------------------------------------ */
/* Token / index bootstrap                                             */
/* ------------------------------------------------------------------ */

const chosen = chooseSourceJar();
const discoveredJars = candidateSourceJars();
let index;
let indexError;
if (chosen.file) {
  try {
    index = new SourceIndex(chosen.file);
  } catch (error) {
    indexError = error;
    log(`failed to index ${chosen.file}: ${error.message}`);
  }
} else {
  indexError = new Error('no ForgeGradle decompiled-sources jar found');
  log(indexError.message);
}

/* ------------------------------------------------------------------ */
/* Tools                                                               */
/* ------------------------------------------------------------------ */

const TOOLS = [
  {
    name: 'status',
    description: 'Report which decompiled Minecraft/Forge sources jar is indexed, its version, and class/package counts. Call this if a lookup seems to be using the wrong Minecraft version.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
  {
    name: 'search_classes',
    description: 'Find Minecraft/Forge classes by fully-qualified, simple, or partial name. Returns class names, source paths, and line counts. Use this first when you only know a class name.',
    inputSchema: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Class name or fragment, e.g. "EntityLivingBase", "net.minecraft.entity", or "block.Block".' },
        package: { type: 'string', description: 'Optional package prefix filter, e.g. "net.minecraft.entity".' },
        limit: { type: 'number', description: 'Maximum classes to return (default 30, max 100).' },
      },
      required: ['query'],
      additionalProperties: false,
    },
  },
  {
    name: 'class_outline',
    description: 'List the fields, constructors, methods, and nested types of one class with 1-based line numbers, without returning the whole file. Use this before reading a large class such as Block, EntityLivingBase, or Minecraft.',
    inputSchema: {
      type: 'object',
      properties: {
        class: { type: 'string', description: 'Fully-qualified class name (preferred) or a unique simple name, e.g. "net.minecraft.entity.EntityLivingBase".' },
        maxMembers: { type: 'number', description: 'Maximum members to list (default 400, max 2000). Large classes are truncated with a note.' },
      },
      required: ['class'],
      additionalProperties: false,
    },
  },
  {
    name: 'read_class',
    description: 'Read a range of one decompiled source file with 1-based line numbers. Always pass startLine/endLine selected from class_outline or search_source; default returns the first 250 lines.',
    inputSchema: {
      type: 'object',
      properties: {
        class: { type: 'string', description: 'Fully-qualified class name (preferred) or unique simple name.' },
        startLine: { type: 'number', description: 'First line to return (1-based, default 1).' },
        endLine: { type: 'number', description: 'Last line to return (inclusive). Defaults to startLine + 249, capped at 800 lines per call.' },
      },
      required: ['class'],
      additionalProperties: false,
    },
  },
  {
    name: 'search_source',
    description: 'Search the decompiled Minecraft/Forge source text. Use for method names, field names, event hooks, strings, or call sites. Returns file:line and a few surrounding lines. Narrow with package/class filters before raising maxResults.',
    inputSchema: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Text or regular expression to search for.' },
        regex: { type: 'boolean', description: 'Treat query as a JavaScript regular expression (default false, literal substring).' },
        caseSensitive: { type: 'boolean', description: 'Case-sensitive matching (default false).' },
        package: { type: 'string', description: 'Optional package prefix filter, e.g. "net.minecraft.entity".' },
        classFilter: { type: 'string', description: 'Optional class-name fragment filter, e.g. "EntityLiving" or "Block".' },
        contextLines: { type: 'number', description: 'Lines of context around each hit (default 1, max 5).' },
        maxResults: { type: 'number', description: 'Maximum hits to return (default 20, max 100).' },
      },
      required: ['query'],
      additionalProperties: false,
    },
  },
  {
    name: 'list_packages',
    description: 'List indexed packages with class counts, optionally filtered by prefix. Use to discover where a feature lives (e.g. "net.minecraftforge.event").',
    inputSchema: {
      type: 'object',
      properties: {
        prefix: { type: 'string', description: 'Optional package prefix, e.g. "net.minecraft.entity" or "net.minecraftforge.event".' },
        limit: { type: 'number', description: 'Maximum packages to return (default 120, max 500).' },
      },
      additionalProperties: false,
    },
  },
];

function requireIndex() {
  if (!index) {
    throw new Error(`Minecraft sources index is unavailable: ${indexError?.message ?? 'unknown error'}. Set MC_SOURCES_JAR to a ForgeGradle *-sources.jar and restart the MCP server.`);
  }
}

function toolStatus() {
  requireIndex();
  return textResult([
    `jar: ${index.jarPath}`,
    `version: ${index.version}`,
    `classes: ${index.byFqn.size}`,
    `packages: ${index.packages.size}`,
    `indexedAt: ${index.indexedAt}`,
    `cachedSources: ${index.textCache.size}`,
    `sourceJarsFound: ${discoveredJars.length}`,
  ].join('\n'));
}

function toolSearchClasses(args) {
  requireIndex();
  const query = String(args.query ?? '').trim();
  if (!query) throw new Error('query is required');
  const q = query.toLowerCase();
  const packageFilter = String(args.package ?? '').trim().toLowerCase();
  const limit = Math.max(1, Math.min(Number(args.limit) || 30, 100));
  const matches = [];
  for (const meta of index.byFqn.values()) {
    if (packageFilter && !meta.fqn.toLowerCase().startsWith(packageFilter)) continue;
    if (!meta.fqn.toLowerCase().includes(q)) continue;
    const score = meta.fqn.toLowerCase() === q ? 0
      : meta.simple.toLowerCase() === q ? 1
        : meta.simple.toLowerCase().startsWith(q) ? 2
          : meta.fqn.toLowerCase().startsWith(q) ? 3 : 4;
    matches.push({ meta, score });
  }
  matches.sort((a, b) => a.score - b.score || a.meta.fqn.length - b.meta.fqn.length || a.meta.fqn.localeCompare(b.meta.fqn));
  if (matches.length === 0) return textResult(`No class matched "${query}"${packageFilter ? ` in ${packageFilter}` : ''}. Try a shorter fragment or list_packages.`);
  const shown = matches.slice(0, limit);
  const lines = shown.map(({ meta }) => `${meta.fqn}  (${meta.entry}, ${meta.size} bytes)`);
  const more = matches.length > shown.length ? `\n[showing ${shown.length} of ${matches.length} matches; raise limit or narrow the query]` : '';
  return textResult(`${lines.join('\n')}${more}`);
}

function toolClassOutline(args) {
  requireIndex();
  const resolved = index.resolve(args.class);
  if (!resolved.meta) return textResult(formatResolveFailure(args.class, resolved), true);
  const outline = index.outline(resolved.meta);
  const maxMembers = Math.max(10, Math.min(Number(args.maxMembers) || 400, 2000));
  const groups = new Map();
  for (const member of outline.members) {
    const bucket = groups.get(member.kind) ?? [];
    bucket.push(member);
    groups.set(member.kind, bucket);
  }
  const parts = [
    `class: ${resolved.meta.fqn}`,
    `source: ${resolved.meta.entry}`,
    `lines: ${outline.totalLines}`,
    `declaration: ${outline.header}`,
    `members: ${outline.members.length}`,
  ];
  let count = 0;
  for (const [kind, members] of groups) {
    parts.push('', `${kind}s (${members.length}):`);
    for (const member of members) {
      if (count >= maxMembers) {
        parts.push(`[stopped after ${maxMembers} members; raise maxMembers or read a range directly]`);
        return textResult(parts.join('\n'));
      }
      parts.push(`  L${member.line}: ${member.signature}`);
      count += 1;
    }
  }
  return textResult(parts.join('\n'));
}

function toolReadClass(args) {
  requireIndex();
  const resolved = index.resolve(args.class);
  if (!resolved.meta) return textResult(formatResolveFailure(args.class, resolved), true);
  const text = index.text(resolved.meta);
  const lines = text.split('\n');
  const total = lines.length;
  let start = Math.max(1, Math.floor(Number(args.startLine) || 1));
  if (start > total) start = Math.max(1, total - 199);
  const requestedEnd = Number(args.endLine);
  let end = Number.isFinite(requestedEnd) && requestedEnd > 0 ? Math.floor(requestedEnd) : start + 249;
  end = Math.min(end, start + 799, total);
  if (end < start) end = Math.min(start + 249, total);
  const header = `// ${resolved.meta.fqn} | ${resolved.meta.entry} | lines ${start}-${end} of ${total}`;
  const footer = end < total ? `\n[next: read_class startLine=${end + 1} endLine=${Math.min(end + 250, total)}]` : '\n[end of file]';
  return textResult(`${header}\n${numberedLines(lines, start, end)}${footer}`);
}

function toolSearchSource(args) {
  requireIndex();
  const query = String(args.query ?? '');
  if (!query) throw new Error('query is required');
  const regex = Boolean(args.regex);
  const caseSensitive = Boolean(args.caseSensitive);
  const packageFilter = String(args.package ?? '').trim();
  const classFilter = String(args.classFilter ?? '').trim().toLowerCase();
  const contextLines = Math.max(0, Math.min(Number(args.contextLines) || 1, 5));
  const maxResults = Math.max(1, Math.min(Number(args.maxResults) || 20, 100));

  let pattern;
  try {
    pattern = regex
      ? new RegExp(query, caseSensitive ? 'u' : 'iu')
      : null;
  } catch (error) {
    return textResult(`Invalid regular expression: ${error.message}`, true);
  }
  const needle = caseSensitive ? query : query.toLowerCase();

  const metas = [...index.byFqn.values()].filter((meta) => {
    if (packageFilter && !meta.fqn.startsWith(packageFilter)) return false;
    if (classFilter && !meta.fqn.toLowerCase().includes(classFilter)) return false;
    return true;
  });
  metas.sort((a, b) => a.fqn.localeCompare(b.fqn));

  const hits = [];
  const output = [];
  let outputLength = 0;
  for (const meta of metas) {
    const lines = index.text(meta).split('\n');
    for (let i = 0; i < lines.length; i += 1) {
      const line = lines[i];
      const matched = pattern ? pattern.test(line) : (caseSensitive ? line.includes(needle) : line.toLowerCase().includes(needle));
      if (!matched) continue;
      hits.push({ meta, line: i + 1 });
      if (hits.length > maxResults) break;
      const from = Math.max(0, i - contextLines);
      const to = Math.min(lines.length - 1, i + contextLines);
      const snippet = [];
      for (let j = from; j <= to; j += 1) {
        snippet.push(`${j === i ? '>' : ' '} ${j + 1}: ${lines[j].trimEnd()}`);
      }
      const block = `\n${meta.entry}:${i + 1}\n${snippet.join('\n')}`;
      output.push(block);
      outputLength += block.length;
      if (outputLength > DEFAULT_MAX_TEXT - 2000) break;
    }
    if (hits.length > maxResults || outputLength > DEFAULT_MAX_TEXT - 2000) break;
  }
  if (hits.length === 0) return textResult(`No match for ${regex ? 'regex' : 'text'} ${JSON.stringify(query)}${packageFilter ? ` in ${packageFilter}` : ''}.`);
  const more = hits.length > maxResults ? `\n[showing first ${maxResults} matches; narrow the filters or raise maxResults]` : '';
  return textResult(clip(`${output.join('\n')}${more}`));
}

function toolListPackages(args) {
  requireIndex();
  const prefix = String(args.prefix ?? '').trim();
  const limit = Math.max(1, Math.min(Number(args.limit) || 120, 500));
  const rows = [...index.packages.entries()]
    .filter(([pkg]) => !prefix || pkg.startsWith(prefix))
    .sort((a, b) => a[0].localeCompare(b[0]))
    .slice(0, limit);
  if (rows.length === 0) return textResult(`No package starts with "${prefix}".`);
  const lines = rows.map(([pkg, count]) => `${pkg}  (${count})`);
  return textResult(`${lines.join('\n')}\n\n[${rows.length} packages shown]`);
}

function formatResolveFailure(input, resolved) {
  if (resolved.total === 0) return `Class "${input}" was not found. Use search_classes to locate the exact name.`;
  if (resolved.total === 1) return `Class "${input}" did not resolve; try search_classes.`;
  const names = resolved.candidates.map((meta) => meta.fqn).join('\n');
  return `Class "${input}" is ambiguous (${resolved.total} candidates). Re-run with a fully-qualified name:\n${names}`;
}

const TOOL_HANDLERS = {
  status: toolStatus,
  search_classes: toolSearchClasses,
  class_outline: toolClassOutline,
  read_class: toolReadClass,
  search_source: toolSearchSource,
  list_packages: toolListPackages,
};

/* ------------------------------------------------------------------ */
/* MCP protocol (stdio, newline-delimited JSON-RPC)                    */
/* ------------------------------------------------------------------ */

const INSTRUCTIONS = `Minecraft 1.12.2 / Forge source index (${index?.version ?? 'unavailable'}).

Use these tools before answering any question about vanilla Minecraft or Forge behaviour, and never invent a method signature or field that you have not read here.

Workflow:
1. search_classes to find the exact class name.
2. class_outline for large classes, then read_class with the member's line number (use startLine/endLine, not whole-file reads).
3. search_source for method names, event hooks, strings, or call sites across all indexed sources.
4. Cite the source path and line numbers in your answer. If a lookup finds nothing, say so instead of guessing.

Coverage: decompiled net.minecraft.* and net.minecraftforge.* for Minecraft 1.12.2 with mappings snapshot_20171003-1.12 and Forge 14.23.5.2854. The project's own code is not in this index; read project files with the normal file tools.`;

function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

function sendResult(id, result) {
  send({ jsonrpc: '2.0', id, result });
}

function sendError(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } });
}

function handleRequest(message) {
  const { id, method, params } = message;
  if (method === 'initialize') {
    sendResult(id, {
      protocolVersion: typeof params?.protocolVersion === 'string' ? params.protocolVersion : MCP_PROTOCOL_FALLBACK,
      capabilities: { tools: { listChanged: false }, resources: { listChanged: false } },
      serverInfo: { name: SERVER_NAME, version: SERVER_VERSION },
      instructions: INSTRUCTIONS,
    });
    return;
  }
  if (method === 'ping') { sendResult(id, {}); return; }
  if (method === 'tools/list') { sendResult(id, { tools: TOOLS }); return; }
  if (method === 'resources/list') { sendResult(id, { resources: [] }); return; }
  if (method === 'resources/templates/list') { sendResult(id, { resourceTemplates: [] }); return; }
  if (method === 'prompts/list') { sendResult(id, { prompts: [] }); return; }
  if (method === 'tools/call') {
    const name = params?.name;
    const handler = TOOL_HANDLERS[name];
    if (!handler) { sendError(id, -32602, `unknown tool: ${name}`); return; }
    try {
      const result = handler(params?.arguments ?? {});
      sendResult(id, result);
    } catch (error) {
      sendResult(id, textResult(`Tool "${name}" failed: ${error?.message ?? String(error)}`, true));
    }
    return;
  }
  sendError(id, -32601, `method not found: ${method}`);
}

let buffer = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buffer += chunk;
  let newlineIndex;
  while ((newlineIndex = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, newlineIndex).trim();
    buffer = buffer.slice(newlineIndex + 1);
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
      if (message.id === undefined || message.id === null) continue; // notification
      handleRequest(message);
    }
  }
});
process.stdin.on('end', () => {
  try { index?.zip.fd && fs.closeSync(index.zip.fd); } catch { /* ignore */ }
  process.exit(0);
});
process.on('SIGTERM', () => process.exit(0));
process.on('SIGINT', () => process.exit(0));

log(`ready: ${index ? index.jarPath : 'no sources indexed'}`);

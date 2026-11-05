import { spawn } from 'node:child_process';
import path from 'node:path';

const child = spawn(process.execPath, ['server.js'], {
  cwd: path.join(import.meta.dirname, '..'),
  stdio: ['pipe', 'pipe', 'pipe'],
});
child.stderr.on('data', (chunk) => process.stderr.write(`[stderr] ${chunk}`));

let nextId = 1;
const pending = new Map();
let buffer = '';
child.stdout.on('data', (chunk) => {
  buffer += chunk;
  let index;
  while ((index = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    if (!line) continue;
    const message = JSON.parse(line);
    if (message.id && pending.has(message.id)) {
      const { resolve, reject } = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) reject(new Error(JSON.stringify(message.error)));
      else resolve(message.result);
    }
  }
});

function request(method, params) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error(`timeout: ${method}`));
      }
    }, 30_000);
  });
}

function show(label, result) {
  const text = result?.content?.[0]?.text ?? JSON.stringify(result);
  console.log(`\n===== ${label} =====\n${text.slice(0, 4000)}`);
}

const init = await request('initialize', {
  protocolVersion: '2025-06-18',
  capabilities: {},
  clientInfo: { name: 'test-client', version: '0.0.1' },
});
console.log('initialize:', JSON.stringify(init).slice(0, 600));
child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);

const tools = await request('tools/list', {});
console.log('tools:', tools.tools.map((tool) => tool.name).join(', '));

show('status', await request('tools/call', { name: 'status', arguments: {} }));
show('search_classes', await request('tools/call', { name: 'search_classes', arguments: { query: 'EntityLivingBase' } }));
show('class_outline', await request('tools/call', { name: 'class_outline', arguments: { class: 'net.minecraft.entity.EntityLivingBase', maxMembers: 25 } }));
show('read_class', await request('tools/call', { name: 'read_class', arguments: { class: 'net.minecraft.entity.EntityLivingBase', startLine: 1, endLine: 25 } }));
show('search_source', await request('tools/call', { name: 'search_source', arguments: { query: 'attackEntityFrom', package: 'net.minecraft.entity', maxResults: 3 } }));

child.stdin.end();
child.kill();


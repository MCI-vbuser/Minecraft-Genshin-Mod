import { spawn } from 'node:child_process';
import path from 'node:path';

const child = spawn(process.execPath, ['idea-bridge.js'], { cwd: path.join(import.meta.dirname, '..'), stdio: ['pipe', 'pipe', 'pipe'] });
child.stderr.on('data', (chunk) => process.stderr.write(`[stderr] ${chunk}`));
let buffer = '';
let nextId = 1;
const pending = new Map();
child.stdout.on('data', (chunk) => {
  buffer += chunk;
  let index;
  while ((index = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    if (!line) continue;
    const message = JSON.parse(line);
    if (message.id && pending.has(message.id)) {
      const resolve = pending.get(message.id);
      pending.delete(message.id);
      resolve(message);
    }
  }
});
function request(method, params) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, resolve);
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    setTimeout(() => { if (pending.has(id)) { pending.delete(id); reject(new Error(`timeout ${method}`)); } }, 60_000);
  });
}
function show(label, response) {
  const result = response.result;
  const text = result?.content?.find((item) => item.type === 'text')?.text ?? JSON.stringify(response);
  console.log(`\n=== ${label} isError=${result?.isError} ===\n${String(text).slice(0, 3000)}`);
}

const init = await request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test', version: '0' } });
console.log('init:', JSON.stringify(init.result).slice(0, 200));
child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);
const list = await request('tools/list', {});
console.log('tools:', list.result.tools.map((tool) => tool.name).join(', '));
show('list_libraries', await request('tools/call', { name: 'list_libraries', arguments: {} }));
show('search_classes', await request('tools/call', { name: 'search_classes', arguments: { query: 'AnimatedGeoModel', limit: 5 } }));
show('class_outline', await request('tools/call', { name: 'class_outline', arguments: { class: 'software.bernie.geckolib3.model.AnimatedGeoModel', maxMembers: 20 } }));
show('read_class', await request('tools/call', { name: 'read_class', arguments: { class: 'software.bernie.geckolib3.model.AnimatedGeoModel', startLine: 36, endLine: 60 } }));
child.stdin.end();
child.kill();

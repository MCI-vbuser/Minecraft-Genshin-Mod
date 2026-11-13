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
      resolve(message.result);
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
function show(label, result) {
  const text = result?.content?.find((item) => item.type === 'text')?.text ?? JSON.stringify(result);
  console.log(`\n=== ${label} ===\n${String(text).slice(0, 1800)}`);
}

await request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test', version: '0' } });
child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);
const list = await request('tools/list', {});
console.log(`tools (${list.tools.length}): ${list.tools.map((tool) => tool.name).join(', ')}`);
console.log('read_file schema:', JSON.stringify(list.tools.find((tool) => tool.name === 'read_file')?.inputSchema));
show('status', await request('tools/call', { name: 'status', arguments: {} }));
const jar = 'D:/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.12.2-14.23.5.2854_mapped_snapshot_20171003-1.12';
show('read_file sources', await request('tools/call', { name: 'read_file', arguments: { file_path: `${jar}/forge-1.12.2-14.23.5.2854_mapped_snapshot_20171003-1.12-sources.jar!/net/minecraft/block/Block.java`, offset: 2640, limit: 20 } }));
show('read_file class', await request('tools/call', { name: 'read_file', arguments: { file_path: `${jar}/forge-1.12.2-14.23.5.2854_mapped_snapshot_20171003-1.12-recomp.jar!/net/minecraft/entity/EntityLivingBase.class`, offset: 1, limit: 12 } }));
show('search_symbol', await request('tools/call', { name: 'search_symbol', arguments: { q: 'EntityLivingBase', include_external: true, limit: 3 } }));
show('modules', await request('tools/call', { name: 'get_project_modules', arguments: {} }));
show('list_libraries', await request('tools/call', { name: 'list_libraries', arguments: {} }));
show('search_classes', await request('tools/call', { name: 'search_classes', arguments: { query: 'AnimatedGeoModel', includeExternal: true, limit: 5 } }));
show('class_outline', await request('tools/call', { name: 'class_outline', arguments: { class: 'software.bernie.geckolib3.model.AnimatedGeoModel', maxMembers: 20 } }));
show('read_class', await request('tools/call', { name: 'read_class', arguments: { class: 'org.h2.Driver', startLine: 1, endLine: 30 } }));
child.stdin.end();
child.kill();


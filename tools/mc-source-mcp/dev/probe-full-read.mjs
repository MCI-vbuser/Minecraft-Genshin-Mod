const url = 'http://127.0.0.1:64342/stream';
let sessionId;
const headers = { 'content-type': 'application/json', accept: 'application/json, text/event-stream', origin: 'http://localhost' };

async function readMessage(response, expectedId) {
  const type = response.headers.get('content-type') ?? '';
  if (!type.includes('text/event-stream')) return response.json();
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let index;
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      const event = buffer.slice(0, index);
      buffer = buffer.slice(index + 2);
      const data = event.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trim()).join('\n');
      if (!data) continue;
      const message = JSON.parse(data);
      if (message.id === expectedId) { await reader.cancel(); return message; }
    }
  }
  throw new Error('no response');
}

async function post(body) {
  const response = await fetch(url, { method: 'POST', headers: { ...headers, ...(sessionId ? { 'mcp-session-id': sessionId, 'mcp-protocol-version': '2025-06-18' } : {}) }, body: JSON.stringify(body) });
  const sid = response.headers.get('mcp-session-id'); if (sid) sessionId = sid;
  return readMessage(response, body.id);
}

await post({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'probe', version: '0' } } });
await post({ jsonrpc: '2.0', method: 'notifications/initialized' });

async function read(label, path, offset, limit) {
  const result = await post({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'read_file', arguments: { file_path: path, offset, limit, projectPath: 'C:/Users/ASUS/Desktop/mdk' } } });
  const text = result.result?.content?.find((item) => item.type === 'text')?.text ?? '';
  const lines = text.split('\n');
  const numbers = [...text.matchAll(/^L(\d+):/gm)].map((match) => Number(match[1]));
  const unique = new Set(numbers);
  const truncated = text.includes('truncated');
  console.log(`\n${label}: chars=${text.length} lines=${lines.length} numbered=${numbers.length} unique=${unique.size} min=${Math.min(...numbers)} max=${Math.max(...numbers)} truncated=${truncated}`);
  console.log('first:', lines.slice(0, 3).join(' | '));
  console.log('last:', lines.slice(-3).join(' | '));
  const missing = [];
  for (let i = 1; i <= Math.max(...numbers); i += 1) if (!unique.has(i)) missing.push(i);
  console.log('missing count:', missing.length, 'first missing:', missing.slice(0, 20).join(','));
}

const jar = 'D:/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.12.2-14.23.5.2854_mapped_snapshot_20171003-1.12';
await read('EntityLivingBase limit5000', `${jar}/forge-1.12.2-14.23.5.2854_mapped_snapshot_20171003-1.12-sources.jar!/net/minecraft/entity/EntityLivingBase.java`, 1, 5000);
await read('LivingHurtEvent limit5000', `${jar}/forge-1.12.2-14.23.5.2854_mapped_snapshot_20171003-1.12-sources.jar!/net/minecraftforge/event/entity/living/LivingHurtEvent.java`, 1, 5000);

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
const list = await post({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} });
const tool = list.result.tools.find((item) => item.name === 'get_project_dependencies');
console.log('schema:', JSON.stringify(tool, null, 2));
const result = await post({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'get_project_dependencies', arguments: { projectPath: 'C:/Users/ASUS/Desktop/mdk' } } });
const text = result.result?.content?.find((item) => item.type === 'text')?.text ?? JSON.stringify(result);
console.log(text.slice(0, 30000));

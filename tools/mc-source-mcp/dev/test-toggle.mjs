import { apply } from '../index.js';

const session = { id: 'test-session' };
const states = new Map();
let projection;
let handle;
let guard;
let section;
let effectDone;

const ctx = {
  effect(fn) {
    const iterator = fn();
    effectDone = (async () => {
      let step = iterator.next();
      while (!step.done) step = iterator.next(await step.value);
    })();
    return () => {};
  },
  sessionProjections: {
    async register(definition) {
      projection = definition;
      states.set(session, definition.init());
      handle = {
        stateOf: (target) => states.get(target),
      };
      return handle;
    },
  },
  commands: { register: () => () => {} },
  systemPrompt: {
    getSectionOrder: () => 0,
    section: (value) => { section = value; return () => {}; },
  },
  tools: { guard: (fn) => { guard = fn; return () => {}; } },
};

apply(ctx);
await effectDone;

function feed(event) {
  states.set(session, projection.apply(states.get(session), event));
}

console.log('initial:', JSON.stringify(states.get(session)));
console.log('guard off:', guard({ name: 'mcp__mc__status', agent: { session } }));
console.log('section off:', JSON.stringify(section.text({ agent: { session } })));

feed({ type: 'command/run', data: { commandId: 'cmd-1', name: 'mc', args: 'on' } });
console.log('after run:', JSON.stringify(states.get(session)));
console.log('view during run:', JSON.stringify(projection.wire.view(states.get(session))));
feed({ type: 'command/done', data: { commandId: 'cmd-1', kind: 'success' } });
console.log('after done:', JSON.stringify(states.get(session)));
console.log('view on:', JSON.stringify(projection.wire.view(states.get(session))));
console.log('section on:', section.text({ agent: { session } }).split('\n')[0]);
console.log('guard on:', guard({ name: 'mcp__mc__status', agent: { session } }));

feed({ type: 'command/run', data: { commandId: 'cmd-2', name: 'mc', args: 'off' } });
feed({ type: 'command/done', data: { commandId: 'cmd-2', kind: 'success' } });
console.log('after off:', JSON.stringify(states.get(session)));
console.log('guard off again:', guard({ name: 'mcp__mc__status', agent: { session } }));

feed({ type: 'command/run', data: { commandId: 'cmd-3', name: 'mc', args: 'on' } });
feed({ type: 'command/done', data: { commandId: 'cmd-3', kind: 'error', text: 'nope' } });
console.log('after failed on:', JSON.stringify(states.get(session)));

/**
 * Host half of the Minecraft Source Bridge toggle.
 *
 * The grass-block button in the composer writes `/mc on` or `/mc off`. Those
 * commands are ordinary session events, so a session projection derives the
 * per-conversation state and survives resume/fork without a private store.
 *
 * While the projection is inactive, the MC/IDEA source tools are denied by a
 * tool guard and no source-bridge section is added to the system prompt. While
 * active, the section tells the model which tools it may use.
 */

export const name = 'mc-source-toggle';
export const inject = ['commands', 'systemPrompt', 'sessionProjections', 'tools'];

const KEY = 'mcSource';
const COMMAND = 'mc';

function assertState(value) {
  if (typeof value !== 'object' || value === null) throw new Error('mcSource state must be an object');
  if (typeof value.active !== 'boolean') throw new Error('mcSource.active must be a boolean');
  if (value.running !== null) {
    if (typeof value.running !== 'object' || typeof value.running.commandId !== 'string' || typeof value.running.wanted !== 'boolean') {
      throw new Error('mcSource.running must be null or { commandId, wanted }');
    }
  }
  return value;
}

function plainSchema(check) {
  return {
    parse(value) {
      return check(value);
    },
    safeParse(value) {
      try {
        return { success: true, data: check(value) };
      } catch (error) {
        return { success: false, error };
      }
    },
  };
}

export async function apply(ctx) {
  const stateSchema = plainSchema(assertState);
  const viewSchema = plainSchema((value) => {
    if (typeof value !== 'object' || value === null || typeof value.active !== 'boolean' || typeof value.pending !== 'boolean') {
      throw new Error('mcSource view must be { active, pending }');
    }
    return value;
  });

  const projection = {
    key: KEY,
    stateVersion: 1,
    stateSchema,
    init: () => ({ active: false, running: null }),
    apply: (state, event) => {
      if (event.type === 'command/run' && event.data.name === COMMAND) {
        const action = String(event.data.args ?? '').trim().toLowerCase();
        if (action !== 'on' && action !== 'off') return state;
        return {
          ...state,
          running: { commandId: event.data.commandId, wanted: action === 'on' },
        };
      }
      if (event.type === 'command/done' && state.running !== null && event.data.commandId === state.running.commandId) {
        return event.data.kind === 'success'
          ? { active: state.running.wanted, running: null }
          : { ...state, running: null };
      }
      return state;
    },
    wire: {
      viewSchema,
      view: (state) => ({
        active: state.active,
        pending: state.running !== null && state.running.wanted !== state.active,
      }),
    },
  };

  let projectionHandle;

  const section = {
    name: 'mc:source-bridge',
    order: ctx.systemPrompt.getSectionOrder('MCP_SERVERS'),
    text: (context) => {
      if (context.agent === undefined) return '';
      const state = projectionHandle?.stateOf(context.agent.session);
      if (state?.active !== true) return '';
      return `## Minecraft source bridge: ON

The user enabled the Minecraft source bridge for this conversation. Use its tools instead of recalling Minecraft APIs from memory, and cite file paths plus line numbers.

- \`mcp__mc__*\` — Minecraft 1.12.2 / Forge 14.23.5.2854 (mappings snapshot_20171003-1.12): \`search_classes\`, \`class_outline\`, \`read_class\`, \`search_source\`, \`list_packages\`.
- \`mcp__idea__*\` — the whole project plus every External Library (GeckoLib, Mixin, H2, Guava, Netty, JDK, ...): \`search_classes\`, \`class_outline\`, \`read_class\`, \`list_libraries\`, plus build/inspection/run tools.

Start with a class search, use the outline before a long class, and read only the line range you need. The grass-block button next to Send turns this off, after which these tools are denied for this conversation.`;
    },
  };

  const command = {
    name: COMMAND,
    description: 'Enable or disable the Minecraft source bridge for this conversation',
    input: { hint: '[on|off]' },
    handler: ({ rawInput }) => {
      const action = String(rawInput ?? '').trim().toLowerCase();
      if (action !== 'on' && action !== 'off') {
        return { kind: 'error', text: 'Usage: /mc on|off' };
      }
      return {
        kind: 'success',
        text: action === 'on'
          ? 'Minecraft source bridge enabled for this conversation.'
          : 'Minecraft source bridge disabled for this conversation.',
      };
    },
  };

  const guard = (execution) => {
    const isSourceTool = execution.name.startsWith('mcp__mc__') || execution.name.startsWith('mcp__idea__');
    if (!isSourceTool) return undefined;
    const agent = execution.agent;
    if (agent === undefined) return undefined;
    let active = false;
    try {
      active = projectionHandle?.stateOf(agent.session)?.active === true;
    } catch {
      active = false;
    }
    return active
      ? undefined
      : 'The Minecraft source bridge is off for this conversation. Turn on the grass-block button next to Send, or run /mc on, before using mcp__mc__* or mcp__idea__* tools.';
  };

  projectionHandle = await ctx.sessionProjections.register(projection);
  ctx.effect(() => ctx.commands.register(command), 'mc-source-toggle: command');
  ctx.effect(() => ctx.systemPrompt.section(section), 'mc-source-toggle: system prompt');
  ctx.effect(() => ctx.tools.guard(guard), 'mc-source-toggle: tool guard');
}

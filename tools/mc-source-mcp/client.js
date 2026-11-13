/**
 * Browser half of the Minecraft Source Bridge.
 *
 * Renders a compact grass-block button in the composer's right control group
 * and toggles the per-conversation source bridge through the normal `/mc`
 * command channel. The Host projection (`mcSource`) is the source of truth for
 * the lit state, so resume/fork and the tool guard stay consistent.
 */
window.__ModuleLoader__.load({
  id: '@local/mc-source',
  factory(require) {
    const React = require('react');
    const h = React.createElement;

    const NS = 'mcSource';
    const zh = {
      'button.off': 'Minecraft 源码桥已关闭，点击开启',
      'button.on': 'Minecraft 源码桥已开启，点击关闭',
      'button.pending': '正在切换 Minecraft 源码桥…',
      'button.failed': '切换失败：{message}',
    };
    const en = {
      'button.off': 'Minecraft source bridge is off. Click to turn on.',
      'button.on': 'Minecraft source bridge is on. Click to turn off.',
      'button.pending': 'Switching the Minecraft source bridge…',
      'button.failed': 'Switch failed: {message}',
    };

    function GrassBlock({ active }) {
      const top = active ? '#7ed957' : '#7a828e';
      const left = active ? '#a5794f' : '#5f6670';
      const right = active ? '#7c5637' : '#4a5058';
      return h('svg', {
        viewBox: '0 0 24 24',
        width: 18,
        height: 18,
        'aria-hidden': true,
        focusable: false,
        style: { display: 'block' },
      },
      h('polygon', { points: '12,3 20,7.5 12,12 4,7.5', fill: top }),
      h('polygon', { points: '4,7.5 12,12 12,20 4,15.5', fill: left }),
      h('polygon', { points: '20,7.5 12,12 12,20 20,15.5', fill: right }),
      h('polygon', { points: '12,12 20,7.5 20,9.4 12,13.9', fill: active ? '#6cc247' : '#6b737c' }),
      h('polygon', { points: '12,12 4,7.5 4,9.4 12,13.9', fill: active ? '#6cc247' : '#6b737c' }));
    }

    function ToggleButton({ useProjection, toggle, t }) {
      const projection = useProjection('mcSource');
      const active = projection?.active === true;
      const [busy, setBusy] = React.useState(false);
      const [error, setError] = React.useState(null);
      const alive = React.useRef(true);
      React.useEffect(() => {
        alive.current = true;
        return () => {
          alive.current = false;
        };
      }, []);

      const pending = busy || projection?.pending === true;
      const label = pending ? t('button.pending') : active ? t('button.on') : t('button.off');
      const title = error === null ? label : `${label} — ${t('button.failed', { message: error })}`;

      const onClick = () => {
        if (pending) return;
        setBusy(true);
        setError(null);
        toggle(!active).then(
          (failure) => {
            if (!alive.current) return;
            setBusy(false);
            setError(failure);
          },
          (reason) => {
            if (!alive.current) return;
            setBusy(false);
            setError(reason instanceof Error ? reason.message : String(reason));
          },
        );
      };

      return h('span', {
        style: { display: 'inline-flex', alignItems: 'center', gap: 2 },
      },
      h('button', {
        type: 'button',
        'aria-label': title,
        'aria-pressed': active,
        title,
        disabled: pending,
        onClick,
        style: {
          width: 28,
          height: 28,
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: 0,
          border: 'none',
          borderRadius: 8,
          background: active ? 'rgba(126, 217, 87, 0.16)' : 'transparent',
          boxShadow: active ? 'inset 0 0 0 1px rgba(126, 217, 87, 0.35)' : 'none',
          color: active ? '#7ed957' : 'var(--dsh-color-text-tertiary, #8a8f98)',
          cursor: pending ? 'progress' : 'pointer',
          opacity: pending ? 0.55 : 1,
          lineHeight: 0,
          transition: 'background 120ms ease, box-shadow 120ms ease, opacity 120ms ease',
        },
      }, h(GrassBlock, { active })),
      error === null ? null : h('span', {
        role: 'status',
        'aria-label': t('button.failed', { message: error }),
        title: error,
        style: {
          width: 6,
          height: 6,
          borderRadius: '50%',
          background: '#e5484d',
          display: 'inline-block',
        },
      }));
    }

    return {
      inject: ['slots', 'remote', 'remote.commands', 'locale'],
      apply(ctx) {
        ctx.effect(() => ctx.locale.register(NS, { zh, en }), 'mc-source: dictionaries');
        ctx.slots.inject('conversation.input.right', () => ctx.slots.register({
          name: 'conversation.input.right',
          id: 'mc-source-toggle',
          order: 10,
          locale: NS,
          inject: (sessionId) => ({
            toggle: async (on) => {
              const line = on ? '/mc on' : '/mc off';
              try {
                const result = await ctx.remote.commands.execute(sessionId, line, []);
                if (result === undefined) return `unknown command: ${line}`;
                if (result.ok !== true) {
                  return result.error?.message ?? `failed to run ${line}`;
                }
                return null;
              } catch (reason) {
                return reason instanceof Error ? reason.message : String(reason);
              }
            },
          }),
        }, ToggleButton));
      },
    };
  },
});

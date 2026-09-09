/*
 * AnyListen Android 页面桥（注入到 WebView 主 world，document 加载早期执行）。
 *
 * 设计依据：any-listen 播放器完全在页面内（new Audio()，不挂 DOM），
 *   并通过 navigator.mediaSession.setActionHandler 暴露控制入口。
 * - 上行：hook window.Audio 捕获实例 -> 事件推送状态给原生 anyListenNative.onMediaState
 * - 下行：hook mediaSession.setActionHandler 保存页面真实处理函数 ->
 *         window.__anylistenBridge.{play,pause,next,prev,seek,stop}
 *
 * WebView 兼容：Android 原生 WebView 不实现 navigator.mediaSession / window.MediaMetadata /
 * HTMLAudioElement.setSinkId，这里为三者都提供垫片（shim），避免页面初始化崩溃。
 * 脚本幂等（window.__anylistenBridgeReady 守卫），可重复注入。
 */
(function () {
  if (window.__anylistenBridgeReady) return;
  window.__anylistenBridgeReady = true;

  /* ==================== WebView 兼容垫片 ==================== */

  // 1. MediaMetadata（页面 modules/player/init/mediaSessionInfo.ts 依赖它）
  if (typeof window.MediaMetadata === 'undefined') {
    window.MediaMetadata = function (obj) {
      this.title    = (obj && obj.title)    || '';
      this.artist   = (obj && obj.artist)   || '';
      this.album    = (obj && obj.album)    || '';
      this.artwork  = (obj && obj.artwork)  || [];
    };
  }

  // 2. navigator.mediaSession（Android WebView 不存在，页面初始化直接崩溃）
  if (typeof navigator.mediaSession === 'undefined') {
    var _msState = 'none';
    var _msMeta = null;
    var _msHandlers = {};
    navigator.mediaSession = {
      get playbackState() { return _msState; },
      set playbackState(v) { _msState = v; },
      get metadata() { return _msMeta; },
      set metadata(v) { _msMeta = v; },
      setActionHandler: function (type, fn) {
        if (typeof fn === 'function') _msHandlers[type] = fn;
        else delete _msHandlers[type];
      },
      setPositionState: function () { /* no-op in WebView */ }
    };
  }

  // 3. setSinkId（WebView 未实现，页面 mediaDevice.ts 调用报错）
  if (typeof HTMLAudioElement.prototype.setSinkId === 'undefined') {
    HTMLAudioElement.prototype.setSinkId = function () { return Promise.resolve(); };
  }

  /* ======================================================== */

  var instances = [];
  var lastJson = '';
  var lastPushAt = 0;
  var THROTTLE_MS = 500;
  var wasPlaying = false;

  function mediaMeta() {
    var out = { title: '', artist: '', album: '' };
    try {
      var m = navigator.mediaSession && navigator.mediaSession.metadata;
      if (m) {
        out.title = m.title || '';
        out.artist = m.artist || '';
        out.album = m.album || '';
      }
    } catch (e) {}
    return out;
  }

  // 主播放器判定：优先"正在播放、非静音、有 src"，否则最近创建的非静音实例
  function pickMain() {
    for (var i = instances.length - 1; i >= 0; i--) {
      var el = instances[i];
      if (el && el.src && !el.paused && !el.muted && !el.ended) return el;
    }
    for (var j = instances.length - 1; j >= 0; j--) {
      var e2 = instances[j];
      if (e2 && e2.src && !e2.muted) return e2;
    }
    return null;
  }

  function collectState(a) {
    var d = (a && isFinite(a.duration) && a.duration > 0) ? a.duration : 0;
    var meta = mediaMeta();
    return {
      playing: !!(a && a.src && !a.paused && !a.ended),
      currentTime: Math.floor(((a && a.currentTime) || 0) * 1000),
      duration: Math.floor(d * 1000),
      title: meta.title || document.title || '',
      artist: meta.artist || ''
    };
  }

  function push(force) {
    var a = pickMain();
    var native = window.anyListenNative;
    if (!a || !native) return;
    var now = Date.now();
    var state = collectState(a);
    // 始终跟踪 playing 状态变化（不受节流影响），确保冷却计时能捕捉到页面真正的起播
    if (state.playing && !wasPlaying) lastPlayTs = now;
    wasPlaying = state.playing;
    if (!force && now - lastPushAt < THROTTLE_MS) return;
    var json = JSON.stringify(state);
    if (!force && json === lastJson) return;
    lastJson = json;
    lastPushAt = now;
    try {
      native.onMediaState(json);
    } catch (e) {}
  }

  function hook(el) {
    instances.push(el);
    [
      'play', 'pause', 'playing', 'ended', 'timeupdate',
      'loadedmetadata', 'durationchange', 'emptied', 'error'
    ].forEach(function (ev) {
      el.addEventListener(ev, function () { push(false); }, false);
    });
  }

  // ---- hook window.Audio ----
  var RealAudio = window.Audio;
  function AnyListenAudio(src) {
    var el = new RealAudio(src);
    hook(el);
    return el;
  }
  AnyListenAudio.prototype = RealAudio.prototype;
  window.Audio = AnyListenAudio;

  // ---- hook mediaSession.setActionHandler，保存页面注册的真实处理函数 ----
  var handlers = {};
  try {
    var origSet = navigator.mediaSession.setActionHandler;
    if (origSet) {
      navigator.mediaSession.setActionHandler = function (type, fn) {
        if (typeof fn === 'function') {
          // pause handler 包裹日志 + 调用栈，溯源谁在触发暂停
          if (type === 'pause') {
            handlers[type] = function () {
              console.log('[bridge-who] pause handler CAUGHT');
              try { throw new Error(); } catch (e) { console.log(e.stack); }
              fn();
            };
          } else {
            handlers[type] = fn;
          }
        } else {
          delete handlers[type];
        }
        return origSet.call(navigator.mediaSession, type, fn);
      };
    }
  } catch (e) {}

  // ---- 原生下行控制入口 ----
  // play/pause 冷却保护：Android WebView 中服务启动/音频焦点变化可能
  // 在 play 后马上触发一次误暂停，导致页面播放器进入 play-pause 死循环。
  var lastPlayTs = 0;
  var PAUSE_COOLDOWN_MS = 2500;

  window.__anylistenBridge = {
    play: function () {
      lastPlayTs = Date.now();
      if (handlers.play) handlers.play();
    },
    pause: function () {
      if (Date.now() - lastPlayTs < PAUSE_COOLDOWN_MS) {
        console.log('[bridge] pause suppressed (cooldown after play)');
        return;
      }
      if (handlers.pause) handlers.pause();
    },
    next: function () { if (handlers.nexttrack) handlers.nexttrack(); },
    prev: function () { if (handlers.previoustrack) handlers.previoustrack(); },
    seek: function (sec) { if (handlers.seekto) handlers.seekto({ seekTime: sec }); },
    stop: function () { if (handlers.stop) handlers.stop(); },
    getState: function () {
      var a = pickMain();
      return a ? JSON.stringify(collectState(a)) : '{}';
    },
    bridgeReady: true
  };

  // 兜底：若注入发生在页面已开始播放之后，主动推一次状态
  try {
    setTimeout(function () { push(true); }, 500);
  } catch (e) {}
})();
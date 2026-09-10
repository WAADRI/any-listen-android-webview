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

  // 保活辅助音频判定：页面为维持 MediaSession 注册会创建一个 2 秒静音音频
  // （assets/medias/Silence02s.mp3）。它短暂"播放中"时若被当成主播放器，
  // 会上报虚假的 playing=true，导致通知栏图标乱跳。
  function isHelperAudio(el) {
    var s = (el && el.src) || '';
    return /silence/i.test(s);
  }

  function durationOf(el) {
    return (el && isFinite(el.duration) && el.duration > 0) ? el.duration : 0;
  }

  // 主播放器判定：优先"正在播放的真实曲目"，其次时长最长的真实曲目，最后兜底。
  function pickMain() {
    var i, el;
    for (i = instances.length - 1; i >= 0; i--) {
      el = instances[i];
      if (el && el.src && !isHelperAudio(el) && !el.paused && !el.muted && !el.ended) return el;
    }
    var best = null, bestDur = -1;
    for (i = 0; i < instances.length; i++) {
      el = instances[i];
      if (!el || !el.src || isHelperAudio(el) || el.muted) continue;
      var d = durationOf(el);
      if (d > bestDur) { best = el; bestDur = d; }
    }
    if (best) return best;
    for (i = instances.length - 1; i >= 0; i--) {
      el = instances[i];
      if (el && el.src && !isHelperAudio(el) && !el.muted) return el;
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

  function sendState(native, json) {
    try {
      native.onMediaState(json);
    } catch (e) {}
  }

  function push(force) {
    var a = pickMain();
    var native = window.anyListenNative;
    if (!native) return;
    var now = Date.now();

    // 没有可识别的真实播放器（换曲清空、src 被移除等）：按"未播放"上报，
    // 保留上一次的标题，避免原生侧状态永久卡在 playing=true。
    if (!a) {
      if (!wasPlaying) return;
      wasPlaying = false;
      var prev = {};
      try { prev = JSON.parse(lastJson || '{}'); } catch (e) {}
      var idle = JSON.stringify({
        playing: false, currentTime: 0, duration: 0,
        title: prev.title || '', artist: prev.artist || ''
      });
      lastJson = idle;
      lastPushAt = now;
      sendState(native, idle);
      return;
    }

    var state = collectState(a);
    var playingChanged = state.playing !== wasPlaying;
    if (playingChanged && state.playing) lastPlayTs = now;
    wasPlaying = state.playing;

    // 关键：播放/暂停态变化必须立即上报，绝不能被节流丢弃。
    // 音频一旦暂停就不再产生 timeupdate/playing 等事件；若这次上报被节流丢掉，
    // 原生侧会永远停留在 playing=true —— 通知栏/锁屏一直是"暂停"图标，
    // 点击只能派发 onPause（页面守卫直接 return），表现为点播放完全无反应。
    if (playingChanged || force) {
      var jsonChanged = JSON.stringify(state);
      lastJson = jsonChanged;
      lastPushAt = now;
      sendState(native, jsonChanged);
      return;
    }

    if (now - lastPushAt < THROTTLE_MS) return;
    var json = JSON.stringify(state);
    if (json === lastJson) return;
    lastJson = json;
    lastPushAt = now;
    sendState(native, json);
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
        if (typeof fn === 'function') handlers[type] = fn;
        else delete handlers[type];
        return origSet.call(navigator.mediaSession, type, fn);
      };
    }
  } catch (e) {}

  // ---- 原生下行控制入口 ----
  // lastPlayTs 仅用于日志：pause 打印距离上次起播的时间差，
  // 若出现用户未操作的 pause 且 dt 很小，说明是系统侧误暂停。
  var lastPlayTs = 0;

  window.__anylistenBridge = {
    play: function () {
      lastPlayTs = Date.now();
      if (handlers.play) handlers.play();
      // 兜底：页面播放器可能只置了播放意图而未真正起播（isEmpty 重新拉流等），
      // 300ms 后若主音频仍为暂停态就直接驱动音频元素，并打印失败原因便于排查。
      setTimeout(function () {
        var a = pickMain();
        if (!a || !a.src) {
          console.log('[bridge] play fallback: no main audio');
          return;
        }
        if (!a.paused) {
          console.log('[bridge] play ok, audio running');
          return;
        }
        console.log('[bridge] play fallback: audio still paused, forcing play()');
        try {
          var p = a.play();
          if (p && p.catch) {
            p.catch(function (e) { console.log('[bridge] force play rejected: ' + e); });
          }
        } catch (e) {
          console.log('[bridge] force play threw: ' + e);
        }
      }, 300);
    },
    pause: function () {
      // 不做任何冷却拦截：用户暂停必须立即生效。
      // （早期为防止音频焦点冲突引发的误暂停曾加过冷却，那种误暂停的根源
      //  requestAudioFocus 冲突已在原生侧移除，冷却只会误伤"恢复后立刻暂停"的正常操作。）
      console.log('[bridge] native pause (dt=' + (Date.now() - lastPlayTs) + 'ms)');
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
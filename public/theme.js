(function () {
  var override;
  function stored(key, fallback) { try { return localStorage.getItem(key) || fallback; } catch (_) { return fallback; } }
  function apply(event) {
    if (event && event.detail) override = event.detail;
    var mode = override && override.mode || stored('gather-theme', 'system');
    var dark = mode === 'dark' || (mode !== 'light' && matchMedia('(prefers-color-scheme: dark)').matches);
    var lightPalette = override && override.light || stored('gather-palette-light', 'paper');
    var darkPalette = override && override.dark || stored('gather-palette-dark', 'midnight');
    var palette = dark ? (darkPalette === 'forest' ? 'forest' : 'midnight') : (lightPalette === 'cloud' ? 'cloud' : 'paper');
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    document.documentElement.dataset.palette = palette;
    document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = { paper: '#F7F4ED', cloud: '#F1F5FA', midnight: '#101925', forest: '#191E1B' }[palette];
  }
  apply();
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', apply);
  window.addEventListener('storage', function () { override = undefined; apply(); });
  window.addEventListener('gather-theme-change', apply);
})();

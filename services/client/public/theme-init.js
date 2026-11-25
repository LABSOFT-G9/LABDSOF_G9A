// Applies the saved or system theme before the first paint. Same rule and key as src/shared/theme.tsx.
(function () {
  try {
    var saved = localStorage.getItem("bmc-theme");
    var dark = saved === "dark" || (saved !== "light" && matchMedia("(prefers-color-scheme: dark)").matches);
    if (dark) document.documentElement.classList.add("dark");
    document.documentElement.style.colorScheme = dark ? "dark" : "light";
    // An explicit choice also sets the browser/status-bar colour (the media-scoped pair covers "system").
    if (saved === "light" || saved === "dark") {
      document.querySelectorAll('meta[name="theme-color"]').forEach(function (meta) {
        meta.setAttribute("content", dark ? "#0a0a0a" : "#ffffff");
      });
    }
  } catch (e) {}
})();

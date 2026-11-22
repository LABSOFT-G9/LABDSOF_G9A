// Applies the saved or system theme before the first paint. Same rule and key as src/shared/theme.tsx.
(function () {
  try {
    var saved = localStorage.getItem("bmc-theme");
    var dark = saved === "dark" || (saved !== "light" && matchMedia("(prefers-color-scheme: dark)").matches);
    if (dark) document.documentElement.classList.add("dark");
    document.documentElement.style.colorScheme = dark ? "dark" : "light";
  } catch (e) {}
})();

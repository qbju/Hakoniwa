// Hakoniwa repo front: 中身は一切プロキシしない（302だけ返す）。
//   /repo/*.apk  → 元の GitHub Release
//   それ以外      → GitHub Pages（ストアページ・署名済みindex・アイコン）
const ORIGIN = "https://qbju.github.io/Hakoniwa";

async function redirects() {
  const r = await fetch(ORIGIN + "/apk-redirects.json", { cf: { cacheTtl: 300, cacheEverything: true } });
  return r.ok ? r.json() : {};
}

export default {
  async fetch(req) {
    const url = new URL(req.url);
    if (req.method !== "GET" && req.method !== "HEAD") return new Response("method not allowed", { status: 405 });
    const path = url.pathname;
    if (path.startsWith("/repo/") && path.endsWith(".apk")) {
      const name = decodeURIComponent(path.slice("/repo/".length));
      const target = (await redirects())[name];
      if (!target || !target.startsWith("https://github.com/")) return new Response("not found", { status: 404 });
      return Response.redirect(target, 302);
    }
    return Response.redirect(ORIGIN + path + url.search, 302);
  },
};

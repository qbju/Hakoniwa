// Hakoniwa front: index/サイトは GitHub Pages から配信し、APK は元の GitHub Release へリダイレクトする。
const ORIGIN = "https://qbju.github.io/Hakoniwa";

async function redirects() {
  const r = await fetch(ORIGIN + "/apk-redirects.json", { cf: { cacheTtl: 300, cacheEverything: true } });
  return r.ok ? r.json() : {};
}

export default {
  async fetch(req) {
    const url = new URL(req.url);
    if (req.method !== "GET" && req.method !== "HEAD") return new Response("method not allowed", { status: 405 });
    let path = url.pathname;
    if (path.startsWith("/repo/") && path.endsWith(".apk")) {
      const name = decodeURIComponent(path.slice("/repo/".length));
      const target = (await redirects())[name];
      if (!target || !target.startsWith("https://github.com/")) return new Response("not found", { status: 404 });
      return Response.redirect(target, 302);
    }
    if (path.endsWith("/")) path += "index.html";
    const res = await fetch(ORIGIN + path, { cf: { cacheTtl: 120, cacheEverything: true } });
    const out = new Response(res.body, res);
    out.headers.set("Access-Control-Allow-Origin", "*");
    return out;
  },
};

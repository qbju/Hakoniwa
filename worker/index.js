// Hakoniwa: ストアページ・署名済みindex・アイコンは Workers Static Assets（public/）から直接配信。
// public/ に無いのは APK だけで、それは元の GitHub Release へ 302 する（APK本体は持たない）。
export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method !== "GET" && req.method !== "HEAD") return new Response("method not allowed", { status: 405 });
    if (url.pathname.startsWith("/repo/") && url.pathname.endsWith(".apk")) {
      const map = await (await env.ASSETS.fetch(new URL("/apk-redirects.json", url))).json().catch(() => ({}));
      const target = map[decodeURIComponent(url.pathname.slice("/repo/".length))];
      if (target && target.startsWith("https://github.com/")) return Response.redirect(target, 302);
    }
    return new Response("not found", { status: 404 });
  },
};

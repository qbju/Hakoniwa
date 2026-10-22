// Hakoniwa: hakoniwa.i2p.workers.dev
// ストアページ(index.html)はWorkerに静的に同梱し、apps.jsonをブラウザで読んで一覧を描く。
// F-Droid index・JSON・アイコンは qbju/Hakoniwa の dist ブランチ（CIが生成）から取得して返す。
// APK は保存も中継もせず、apk-redirects.json に従って元の GitHub Release へ 302。
// Workerは一度デプロイすれば再デプロイ不要（アプリ追加は dist の更新だけで反映）。
import PAGE from "./index.html";
import I32 from "./icons/i32.png";
import I192 from "./icons/i192.png";
import I512 from "./icons/i512.png";
import M512 from "./icons/m512.png";
import APPLE from "./icons/apple.png";
import ICO from "./icons/favicon.ico";

const MANIFEST = JSON.stringify({
  name: "Hakoniwa — OSS Androidアプリの箱庭", short_name: "Hakoniwa", lang: "ja",
  description: "GitHub Releases の OSS Android アプリを日本語で探せる F-Droid 互換ストア",
  start_url: "/", scope: "/", display: "standalone", background_color: "#ffffff", theme_color: "#1976D2",
  icons: [
    { src: "/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
    { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
    { src: "/icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
  ],
});
// オフライン時だけトップを出す最小限のService Worker
const SW = `const C="hakoniwa-v1";
self.addEventListener("install",e=>{e.waitUntil(caches.open(C).then(c=>c.add("/")));self.skipWaiting()});
self.addEventListener("activate",e=>e.waitUntil(self.clients.claim()));
self.addEventListener("fetch",e=>{if(e.request.mode==="navigate")e.respondWith(fetch(e.request).catch(()=>caches.match("/")))});`;
const STATIC = {
  "/favicon.ico": [ICO, "image/x-icon"], "/favicon-32.png": [I32, "image/png"],
  "/icon-192.png": [I192, "image/png"], "/icon-512.png": [I512, "image/png"],
  "/icon-maskable-512.png": [M512, "image/png"], "/apple-touch-icon.png": [APPLE, "image/png"],
  "/manifest.webmanifest": [MANIFEST, "application/manifest+json; charset=utf-8"],
  "/sw.js": [SW, "text/javascript; charset=utf-8"],
};

const RAW = "https://raw.githubusercontent.com/qbju/Hakoniwa/dist";
const TYPES = {
  html: "text/html; charset=utf-8", json: "application/json; charset=utf-8", jar: "application/java-archive",
  png: "image/png", svg: "image/svg+xml", webp: "image/webp", xml: "application/xml",
  txt: "text/plain; charset=utf-8", css: "text/css; charset=utf-8", js: "text/javascript; charset=utf-8",
};
const short = (p) => p.endsWith(".html") || p.endsWith(".json") || p.endsWith(".jar");

async function raw(path, ttl) {
  return fetch(RAW + path, { cf: { cacheTtl: ttl, cacheEverything: true } });
}

export default {
  async fetch(req) {
    if (req.method !== "GET" && req.method !== "HEAD") return new Response("method not allowed", { status: 405 });
    let path = new URL(req.url).pathname;
    if (path.includes("..") || path.startsWith("/_worker")) return new Response("not found", { status: 404 });

    const st = STATIC[path];
    if (st) {
      return new Response(req.method === "HEAD" ? null : st[0], {
        headers: { "content-type": st[1], "cache-control": path === "/sw.js" ? "no-cache" : "public, max-age=86400", "x-content-type-options": "nosniff" },
      });
    }

    if (path.startsWith("/repo/") && path.endsWith(".apk")) {
      const r = await raw("/apk-redirects.json", 300);
      const map = r.ok ? await r.json() : {};
      const target = map[decodeURIComponent(path.slice(6))];
      return target ? Response.redirect(target, 302) : new Response("not found", { status: 404 });
    }

    if (path === "/" || path === "/index.html" || path === "/about" || path === "/about/" || /^\/app\/[A-Za-z0-9_.]+\/?$/.test(path)) {
      return new Response(req.method === "HEAD" ? null : PAGE, {
        headers: { "content-type": TYPES.html, "cache-control": "public, max-age=300", "x-content-type-options": "nosniff" },
      });
    }
    if (path.endsWith("/")) path += "index.html";
    let r = await raw(path, short(path) ? 300 : 86400);
    if (r.status === 404 && !path.split("/").pop().includes(".")) {
      path += "/index.html";
      r = await raw(path, 300);
    }
    if (!r.ok) return new Response("not found", { status: 404 });
    const ext = path.split(".").pop().toLowerCase();
    return new Response(req.method === "HEAD" ? null : r.body, {
      headers: {
        "content-type": TYPES[ext] || "application/octet-stream",
        "cache-control": short(path) ? "public, max-age=300" : "public, max-age=86400",
        "access-control-allow-origin": "*",
        "x-content-type-options": "nosniff",
      },
    });
  },
};

// Issue #225: load the consumer's Wasm distribution in headless Chrome, wait for Kool to report
// that it has drawn frames, then check from the page itself that the canvas holds a WebGL 2
// context, screenshot it, and check the screenshot shows a scene rather than an empty page.
//
//     node draw-check.mjs <chrome binary> <dist dir> <out.png>
//
// Chrome DevTools Protocol over Node's built-in WebSocket; no npm packages. Exits non-zero, with
// the reason, on every failure.
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { extname, join, normalize } from 'node:path';
import { inflateSync } from 'node:zlib';

const [chromeBin, distDir, outPng] = process.argv.slice(2);
if (!chromeBin || !distDir || !outPng) {
    console.error('usage: node draw-check.mjs <chrome> <dist dir> <out.png>');
    process.exit(2);
}
const READY_TIMEOUT_MS = 120_000;
const WIDTH = 800;
const HEIGHT = 600;

const cleanups = [];
function fail(msg) {
    console.error(`FAIL: ${msg}`);
    for (const c of cleanups.reverse()) c();
    process.exit(1);
}

// --- static server --------------------------------------------------------------------------
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json' };
const server = createServer((req, res) => {
    const path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname)).replace(/^(\.\.[/\\])+/, '');
    const file = join(distDir, path === '/' ? 'index.html' : path);
    try {
        const body = readFileSync(file);
        res.writeHead(200, { 'Content-Type': MIME[extname(file)] ?? 'application/octet-stream' });
        res.end(body);
    } catch {
        res.writeHead(404);
        res.end();
    }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
cleanups.push(() => server.close());
const pageUrl = `http://127.0.0.1:${server.address().port}/index.html`;

// --- chrome ---------------------------------------------------------------------------------
const profile = mkdtempSync(join(tmpdir(), 'issue225-chrome-'));
cleanups.push(() => rmSync(profile, { recursive: true, force: true }));
const chromeArgs = [
    '--headless=new', '--no-sandbox', '--no-first-run', '--no-default-browser-check',
    '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
    `--window-size=${WIDTH},${HEIGHT}`, `--user-data-dir=${profile}`,
    '--remote-debugging-port=0', 'about:blank',
];
console.log(`chrome flags: ${chromeArgs.join(' ')}`);
const chrome = spawn(chromeBin, chromeArgs, { stdio: ['ignore', 'ignore', 'pipe'] });
cleanups.push(() => chrome.kill('SIGKILL'));
const wsUrl = await new Promise((resolve, reject) => {
    let buf = '';
    const t = setTimeout(() => reject(new Error('Chrome did not print a DevTools URL')), 30_000);
    chrome.stderr.on('data', (d) => {
        buf += d;
        const m = buf.match(/DevTools listening on (ws:\/\/\S+)/);
        if (m) { clearTimeout(t); resolve(m[1]); }
    });
    chrome.on('exit', (code) => reject(new Error(`Chrome exited with ${code}: ${buf}`)));
}).catch((e) => fail(e.message));

// --- CDP ------------------------------------------------------------------------------------
const ws = new WebSocket(wsUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = () => j(new Error('CDP connect failed')); }).catch((e) => fail(e.message));
let nextId = 1;
const pending = new Map();
const listeners = [];
ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
        const { resolve, reject } = pending.get(msg.id);
        pending.delete(msg.id);
        msg.error ? reject(new Error(`${JSON.stringify(msg.error)}`)) : resolve(msg.result);
    } else if (msg.method) {
        for (const l of listeners) l(msg);
    }
};
function send(method, params = {}, sessionId) {
    const id = nextId++;
    ws.send(JSON.stringify({ id, method, params, sessionId }));
    return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}

const { targetId } = await send('Target.createTarget', { url: 'about:blank', newWindow: false });
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
const s = (m, p) => send(m, p, sessionId);

let resolveReady;
const ready = new Promise((r) => { resolveReady = r; });
listeners.push((msg) => {
    if (msg.sessionId !== sessionId) return;
    if (msg.method === 'Runtime.consoleAPICalled') {
        const text = msg.params.args.map((a) => a.value ?? a.description ?? '').join(' ');
        console.log(`[console.${msg.params.type}] ${text}`);
        if (text.includes('KOOL_SPIKE_READY')) resolveReady(text);
    } else if (msg.method === 'Runtime.exceptionThrown') {
        const d = msg.params.exceptionDetails;
        console.log(`[exception] ${d.exception?.description ?? d.text}`);
    }
});
await s('Runtime.enable');
await s('Page.enable');
await s('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: HEIGHT, deviceScaleFactor: 1, mobile: false });
console.log(`navigate: ${pageUrl}`);
await s('Page.navigate', { url: pageUrl });

const readyLine = await Promise.race([
    ready,
    new Promise((r) => setTimeout(() => r(null), READY_TIMEOUT_MS)),
]);
if (!readyLine) fail(`no KOOL_SPIKE_READY on the console within ${READY_TIMEOUT_MS} ms`);

// --- WebGL 2, asked of the page rather than assumed ----------------------------------------
// getContext on a canvas that already has a context returns that context when the type matches,
// and null when it does not. So `webgl2` returning a WebGL2RenderingContext while `webgl`
// returns null says Kool's canvas holds a WebGL 2 context, and nothing else.
const probe = await s('Runtime.evaluate', {
    returnByValue: true,
    expression: `(() => {
        const c = document.getElementById('glCanvas');
        const gl2 = c.getContext('webgl2');
        const gl1 = c.getContext('webgl');
        if (!gl2) return { webgl2: false, webgl1Null: gl1 === null };
        const dbg = gl2.getExtension('WEBGL_debug_renderer_info');
        return {
            webgl2: gl2 instanceof WebGL2RenderingContext,
            webgl1Null: gl1 === null,
            version: gl2.getParameter(gl2.VERSION),
            glsl: gl2.getParameter(gl2.SHADING_LANGUAGE_VERSION),
            renderer: dbg ? gl2.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl2.getParameter(gl2.RENDERER),
            drawingBuffer: gl2.drawingBufferWidth + 'x' + gl2.drawingBufferHeight,
        };
    })()`,
});
const gl = probe.result.value;
console.log(`page WebGL probe: ${JSON.stringify(gl)}`);
if (!gl.webgl2) fail('the canvas has no WebGL 2 context');
if (!gl.webgl1Null) fail('the canvas answered getContext("webgl") too, so its context is not WebGL 2');
if (!/^WebGL 2\.0/.test(gl.version)) fail(`gl.VERSION is "${gl.version}", not WebGL 2.0`);

// --- screenshot, and a look at it -----------------------------------------------------------
const shot = await s('Page.captureScreenshot', { format: 'png' });
const png = Buffer.from(shot.data, 'base64');
writeFileSync(outPng, png);
console.log(`screenshot: ${outPng} (${png.length} bytes)`);

const img = decodePng(png);
const px = (x, y) => { const i = (y * img.width + x) * img.channels; return [img.data[i], img.data[i + 1], img.data[i + 2]]; };
const corners = [px(5, 5), px(img.width - 6, 5), px(5, img.height - 6), px(img.width - 6, img.height - 6)];
const centre = px(img.width >> 1, img.height >> 1);
const dist = (a, b) => Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
const centreColours = new Set();
for (let y = img.height / 2 - 60; y < img.height / 2 + 60; y += 4) {
    for (let x = img.width / 2 - 60; x < img.width / 2 + 60; x += 4) centreColours.add(px(x | 0, y | 0).join(','));
}
console.log(`image ${img.width}x${img.height}; corners ${JSON.stringify(corners)}; centre ${JSON.stringify(centre)}; distinct colours near centre: ${centreColours.size}`);
if (corners.some((c) => dist(c, corners[0]) > 6)) fail('the corners disagree: no uniform clear colour');
if (dist(corners[0], [0, 0, 0]) < 30) fail('the corners are black: the page background, not Kool\'s clear colour');
if (dist(centre, corners[0]) < 30) fail('the centre is the clear colour: the cube was not drawn');
if (centreColours.size < 3) fail('fewer than 3 colours near the centre: not a vertex-coloured cube');

console.log('DRAW CHECK OK: Kool drew on WebGL 2 in headless Chrome');
for (const c of cleanups.reverse()) c();
ws.close();
process.exit(0);

// Minimal PNG decoder: 8-bit RGB/RGBA, non-interlaced, which is what Chrome's screenshots are.
function decodePng(buf) {
    let off = 8;
    let width, height, colourType, bitDepth, interlace;
    const idat = [];
    while (off < buf.length) {
        const len = buf.readUInt32BE(off);
        const type = buf.toString('ascii', off + 4, off + 8);
        const data = buf.subarray(off + 8, off + 8 + len);
        if (type === 'IHDR') {
            width = data.readUInt32BE(0); height = data.readUInt32BE(4);
            bitDepth = data[8]; colourType = data[9]; interlace = data[12];
        } else if (type === 'IDAT') idat.push(data);
        else if (type === 'IEND') break;
        off += 12 + len;
    }
    if (bitDepth !== 8 || interlace !== 0 || (colourType !== 2 && colourType !== 6)) {
        fail(`unsupported PNG: depth ${bitDepth} colour type ${colourType} interlace ${interlace}`);
    }
    const channels = colourType === 6 ? 4 : 3;
    const raw = inflateSync(Buffer.concat(idat));
    const stride = width * channels;
    const out = Buffer.alloc(height * stride);
    for (let y = 0; y < height; y++) {
        const filter = raw[y * (stride + 1)];
        const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
        for (let x = 0; x < stride; x++) {
            const a = x >= channels ? out[y * stride + x - channels] : 0;
            const b = y > 0 ? out[(y - 1) * stride + x] : 0;
            const c = x >= channels && y > 0 ? out[(y - 1) * stride + x - channels] : 0;
            let v;
            switch (filter) {
                case 0: v = line[x]; break;
                case 1: v = line[x] + a; break;
                case 2: v = line[x] + b; break;
                case 3: v = line[x] + ((a + b) >> 1); break;
                case 4: {
                    const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                    v = line[x] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                    break;
                }
                default: fail(`bad PNG filter ${filter}`);
            }
            out[y * stride + x] = v & 0xff;
        }
    }
    return { width, height, channels, data: out };
}

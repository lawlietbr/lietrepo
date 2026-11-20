// Run with Node and curl. Never saves authorization tokens or signed media URLs.
import {execFileSync} from 'node:child_process';
import {createDecipheriv} from 'node:crypto';

const panel = process.argv[2];
if (!panel) throw new Error('Usage: node tools/ultracine-probe.mjs <published-panel-url>');
function http(url, {headers = {}, body, binary = false} = {}) {
  const args = ['--silent', '--show-error', '--fail', '--location', '--max-time', '30'];
  for (const [key, value] of Object.entries(headers)) args.push('-H', `${key}: ${value}`);
  if (body !== undefined) args.push('-H', 'Content-Type: application/json', '--data-binary', JSON.stringify(body));
  args.push(url);
  try {
    return execFileSync('curl.exe', args, {encoding: binary ? undefined : 'utf8', maxBuffer: 4 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe']});
  } catch (error) {
    throw new Error(`HTTP request failed for ${new URL(url).hostname}; curl exit ${error.status}`);
  }
}
const json = (url, options) => JSON.parse(http(url, options));
const rot = (x, n) => (x << n | x >>> (32 - n)) >>> 0;
function round(e) {
  e[0] += e[1]; e[3] = rot(e[3] ^ e[0], 16);
  e[2] += e[3]; e[1] = rot(e[1] ^ e[2], 12);
  e[0] += e[1]; e[3] = rot(e[3] ^ e[0], 8);
  e[2] += e[3]; e[1] = rot(e[1] ^ e[2], 7);
}
function verify(nonce, solution, difficulty) {
  const e = new Uint32Array([1779033703, 3144134277, 1013904242, 2773480762]);
  for (const c of `${nonce}:${solution}`) { e[0] += c.charCodeAt(0) & 255; e[0] = rot(e[0], 7); round(e); }
  for (let i = 0; i < 8; i++) round(e);
  const memory = new Uint32Array(512);
  for (let i = 0; i < 512; i++) { round(e); memory[i] = e[0] ^ e[2]; }
  for (let pass = 0; pass < 2; pass++) for (let i = 0; i < 512; i++) {
    let c = rot(memory[i] + memory[memory[i] & 511], 13);
    c ^= Math.imul(memory[(i + 1) & 511], 2654435761);
    memory[i] = c; e[0] ^= c; round(e);
  }
  let zeros = 0;
  for (let i = 0; i < 8; i++) {
    round(e); let s = e[0];
    for (let j = 0; j < 64; j++) { const v = memory[i * 64 + j]; s = rot(s + v, 5) ^ Math.imul(v, 2246822519); }
    const value = (s ^ e[2]) >>> 0;
    zeros += Math.clz32(value);
    if (value) break;
  }
  return zeros >= difficulty;
}
async function main() {
  const page = http(panel);
  const source = [...page.matchAll(/data-source="([^"]+)"/g)].map(x => x[1]).find(x => new URL(x).hostname.includes('byse'));
  if (!source) throw new Error('This episode/film has no published Byse server');
  const base = new URL(source).origin, code = new URL(source).pathname.split('/')[2];
  const frame = json(`${base}/api/videos/${code}/embed/details`).embed_frame_url;
  const frameBase = new URL(frame).origin;
  const headers = {'Referer': frame, 'X-Embed-Origin': new URL(panel).hostname, 'X-Embed-Referer': panel, 'X-Embed-Parent': source};
  const challenge = json(`${base}/api/videos/${code}/embed/captcha`, {headers, body: {}});
  if (challenge.pow_difficulty > 20) throw new Error('Unsupported challenge difficulty');
  const started = Date.now(); let solution = 0;
  while (!verify(challenge.pow_nonce, solution, challenge.pow_difficulty)) {
    if (++solution % 1024 === 0 && Date.now() - started > 90000) throw new Error('Challenge timed out');
  }
  const verified = json(`${base}/api/videos/${code}/embed/captcha/verify`, {headers, body: {pow_token: challenge.pow_token, solution: String(solution)}});
  if (verified.status !== 'ok') throw new Error('Challenge rejected');
  const {playback: p} = json(`${frameBase}/api/videos/${code}/embed/playback`, {headers: {...headers, 'X-Captcha-Token': verified.token}, body: {fingerprint: {}}});
  const version = Number(p.version);
  if (!(version >= 1 && version <= 20 && p.key_parts.length === 30)) throw new Error('Unsupported key version');
  const key = Buffer.concat([p.key_parts[version - 1], p.key_parts[30 - version]].map(x => Buffer.from(x, 'base64url')));
  const payload = Buffer.from(p.payload, 'base64url');
  const cipher = createDecipheriv('aes-256-gcm', key, Buffer.from(p.iv, 'base64url'));
  cipher.setAuthTag(payload.subarray(-16));
  const plain = JSON.parse(Buffer.concat([cipher.update(payload.subarray(0, -16)), cipher.final()]));
  const mediaHeaders = {Referer: `${frameBase}/`};
  const master = http(plain.sources[0].url, {headers: mediaHeaders});
  if (!master.startsWith('#EXTM3U')) throw new Error('Invalid HLS master');
  const variantUrl = new URL(master.split(/\r?\n/).find(x => x && !x.startsWith('#')), plain.sources[0].url).href;
  const variant = http(variantUrl, {headers: mediaHeaders});
  if (!variant.startsWith('#EXTM3U')) throw new Error('Invalid HLS variant');
  const segmentUrl = new URL(variant.split(/\r?\n/).find(x => x && !x.startsWith('#')), variantUrl).href;
  const segment = http(segmentUrl, {headers: {...mediaHeaders, Range: 'bytes=0-65535'}, binary: true});
  if (!(segment[0] === 0x47 && segment[188] === 0x47)) throw new Error('Segment is not MPEG-TS');
  console.log(JSON.stringify({panel, challenge: 'accepted', keyVersion: version, hls: 'valid', segment: 'MPEG-TS', bytes: segment.length, resolution: master.match(/RESOLUTION=([\dx]+)/)?.[1]}, null, 2));
}
main().catch(error => { console.error(error.message.split('\n')[0]); process.exitCode = 1; });

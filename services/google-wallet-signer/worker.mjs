// Optional Cloudflare signing service. Secrets: GOOGLE_PRIVATE_KEY (PKCS#8 PEM), GOOGLE_CLIENT_EMAIL.
// Vars: GOOGLE_ISSUER_ID, GOOGLE_CLASS_ID, WALLET_ORIGINS. A RATE_LIMITER binding is mandatory.
// No ticket contents are logged or stored; never place the private key in the Android app.
const formats = { qr: 'QR_CODE', aztec: 'AZTEC', pdf417: 'PDF_417', code128: 'CODE_128' };
const b64 = data => btoa(String.fromCharCode(...new Uint8Array(data))).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
const encoded = value => b64(new TextEncoder().encode(JSON.stringify(value)));
const text = (v, max = 200) => typeof v === 'string' && v.trim().length > 0 && v.length <= max;

export function validate(input) {
  if (!input || !text(input.id, 100) || !/^[a-zA-Z0-9_-]+$/.test(input.id) || !text(input.title) || !text(input.operator)) throw new Error('fields');
  if (!formats[input.barcode?.type] || !text(input.barcode?.value, 4096)) throw new Error('barcode');
  for (const field of ['origin', 'destination']) if (input[field] !== undefined && !text(input[field])) throw new Error(field);
  for (const field of ['validFrom', 'validUntil']) if (input[field] !== undefined && (!text(input[field], 40) || !Number.isFinite(Date.parse(input[field])))) throw new Error(field);
  if (input.validFrom && input.validUntil && Date.parse(input.validFrom) > Date.parse(input.validUntil)) throw new Error('dates');
  return input;
}
export function makeClaims(input, env, now = Math.floor(Date.now() / 1000)) {
  const data = validate(input);
  if (!/^\d+$/.test(env.GOOGLE_ISSUER_ID) || !env.GOOGLE_CLASS_ID.startsWith(env.GOOGLE_ISSUER_ID + '.')) throw new Error('issuer');
  const obj = {
    id: `${env.GOOGLE_ISSUER_ID}.${data.id}`, classId: env.GOOGLE_CLASS_ID, state: 'ACTIVE',
    cardTitle: { defaultValue: { language: 'en', value: data.operator } },
    header: { defaultValue: { language: 'en', value: data.title } },
    barcode: { type: formats[data.barcode.type], value: data.barcode.value },
    textModulesData: [{ id: 'copy', header: 'Publit', body: 'A copy of your ticket. Only the ticket issued by the operator is valid; keep the original with you.' }],
  };
  if (data.origin || data.destination) obj.textModulesData.unshift({ id: 'route', header: 'Route', body: [data.origin, data.destination].filter(Boolean).join(' → ') });
  if (data.validFrom || data.validUntil) obj.validTimeInterval = {
    ...(data.validFrom && { start: { date: data.validFrom } }), ...(data.validUntil && { end: { date: data.validUntil } }),
  };
  return { iss: env.GOOGLE_CLIENT_EMAIL, aud: 'google', typ: 'savetowallet', iat: now, exp: now + 600,
    origins: env.WALLET_ORIGINS.split(',').map(x => x.trim()).filter(Boolean), payload: { genericObjects: [obj] } };
}
export async function signClaims(claims, pem) {
  const der = Uint8Array.from(atob(pem.replace(/-----[^-]+-----|\s/g, '')), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const body = `${encoded({ alg: 'RS256', typ: 'JWT' })}.${encoded(claims)}`;
  return `${body}.${b64(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(body)))}`;
}
export default { async fetch(request, env) {
  const response = (body, status) => Response.json(body, { status, headers: { 'Cache-Control': 'no-store' } });
  if (request.method !== 'POST' || new URL(request.url).pathname !== '/sign') return response({ error: 'not found' }, 404);
  if (!env.RATE_LIMITER || !env.GOOGLE_PRIVATE_KEY || !env.GOOGLE_CLIENT_EMAIL || !env.WALLET_ORIGINS) return response({ error: 'not configured' }, 503);
  const limit = await env.RATE_LIMITER.limit({ key: request.headers.get('CF-Connecting-IP') || 'unknown' });
  if (!limit.success) return response({ error: 'rate limit' }, 429);
  if (!request.headers.get('Content-Type')?.startsWith('application/json')) return response({ error: 'content type' }, 415);
  // Bound the actual body, not just the caller-supplied Content-Length header.
  const reader = request.body?.getReader(); if (!reader) return response({ error: 'body' }, 400);
  let length = 0; const chunks = [];
  while (true) { const {done, value} = await reader.read(); if (done) break; length += value.length;
    if (length > 16384) { await reader.cancel(); return response({ error: 'too large' }, 413); } chunks.push(value); }
  let input;
  try { const bytes = new Uint8Array(length); let at = 0; for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.length; }
    input = validate(JSON.parse(new TextDecoder().decode(bytes))); } catch { return response({ error: 'invalid pass' }, 400); }
  try { const jwt = await signClaims(makeClaims(input, env), env.GOOGLE_PRIVATE_KEY);
    return response({ saveUrl: `https://pay.google.com/gp/v/save/${jwt}` }, 200);
  } catch { return response({ error: 'signing unavailable' }, 503); }
} };

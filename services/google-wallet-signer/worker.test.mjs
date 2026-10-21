import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import worker, {validate, makeClaims, signClaims} from './worker.mjs';
const input = { id: 'ticket-1', title: 'Day ticket', operator: 'Test operator', barcode: { type: 'qr', value: 'DEMO-NOT-A-TICKET' } };
const env = { GOOGLE_ISSUER_ID: '123', GOOGLE_CLASS_ID: '123.publit', GOOGLE_CLIENT_EMAIL: 'test@example.invalid', WALLET_ORIGINS: 'https://example.invalid' };
test('rejects arbitrary IDs and unsupported formats', () => {
  assert.throws(() => validate({...input, id: '../x'}));
  assert.throws(() => validate({...input, barcode: {type: 'unknown', value: 'x'}}));
  assert.throws(() => validate({...input, barcode: {type: 'qr', value: 'x'.repeat(4097)}}));
  assert.throws(() => validate({...input, validFrom: 'not a date'}));
});
test('claims have fixed issuer, correct barcode, short expiry, no document or passenger', () => {
  const c = makeClaims({...input, passenger: 'ignored', document: 'ignored'}, env, 1000);
  assert.equal(c.exp, 1600); assert.equal(c.payload.genericObjects[0].id,'123.ticket-1');
  assert.equal(c.payload.genericObjects[0].barcode.type,'QR_CODE');
  assert.ok(!JSON.stringify(c).includes('ignored'));
});
test('RS256 signature verifies with an ephemeral test key', async () => {
  const {privateKey, publicKey} = generateKeyPairSync('rsa', {modulusLength: 2048});
  const jwt = await signClaims(makeClaims(input, env), privateKey.export({type:'pkcs8',format:'pem'}));
  const [h,p,s] = jwt.split('.'); assert.ok(verify('RSA-SHA256',Buffer.from(`${h}.${p}`),publicKey,Buffer.from(s,'base64url')));
});
test('unconfigured service fails closed', async () => {
  assert.equal((await worker.fetch(new Request('https://example.invalid/sign',{method:'POST'}), {})).status,503);
});

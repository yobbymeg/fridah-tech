/*
 * ============================================================
 *  FRIDAH ONLINE NET — Backend
 *  Real auth (phone + 4-digit PIN + device IMEI)
 *  Free-net registry (1 session/day, 20 min, 3 days per IMEI)
 *  SwiftWallet STK Push + signed webhook + LIVE SSE confirmations
 *  Admin panel (users, live payments, maintenance, settings)
 *  App config + update feed (GitHub releases aware)
 *  Deploy target: https://fridahyobbytech.onrender.com
 * ============================================================
 */
'use strict';

const express = require('express');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const https = require('https');

const PORT = process.env.PORT || 3000;
const PUBLIC_URL = (process.env.PUBLIC_URL || 'https://fridahyobbytech.onrender.com').replace(/\/+$/, '');
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const DB_PATH = path.join(DATA_DIR, 'data.json');
const SW_BASE = (process.env.SW_BASE_URL || 'https://swiftwallet.co.ke').replace(/\/+$/, '');

/* ------------------------- persistence ------------------------- */
const DEFAULT_CONFIG = {
  maintenance: { on: false, message: 'Fridah Tech is under maintenance. Please check back soon.' },
  freenet: { minutes: 20, mbCap: 40, days: 3 },
  packages: [
    { id: 'p1', name: '1 Hour Unlimited',  hours: 1,  price: 11,  badge: '' },
    { id: 'p2', name: '3 Hours Unlimited', hours: 3,  price: 23,  badge: '' },
    { id: 'p3', name: '6 Hours Unlimited', hours: 6,  price: 55,  badge: 'MOST POPULAR' },
    { id: 'p4', name: '12 Hours Unlimited', hours: 12, price: 88, badge: 'BEST VALUE' },
    { id: 'p5', name: '1 Day Unlimited',  hours: 24, price: 100, badge: '' }
  ],
  reels: [
    'https://www.facebook.com/reel/699252762734193',
    'https://www.facebook.com/reel/1325453498669591'
  ],
  messages: {
    freeNet: 'You have received {mb}MBs free — enjoy! You can also use this internet on other apps like WhatsApp, Instagram, TikTok and more.',
    freeOver: 'Free net is over. Buy net!',
    useAnotherSim: 'Please use another sim card',
    welcomeTitle: 'Welcome to the home of tech',
    welcomeBody: 'Fast, private and unlimited internet for everyone.',
    adminPhone: '0795314221'
  },
  swiftwallet: { apiKey: '', webhookSecret: '' },
  githubRepo: 'yobbymeg/fridah-tech',
  update: { code: 0, name: '', apkUrl: '' }
};

const db = {
  users: [],           // {id, phone, pinHash, salt, imei, device, createdAt, lastSeen, freeNetDays:{}, activePackage:{}}
  sessions: {},        // token -> {userId, createdAt}
  payments: [],        // {id, userId, phone, packageId, amount, status, sw:{...}, createdAt, completedAt, receipt}
  imei: {},            // imei -> {days:{dateStr:{startedAt,minutes,mbCap,stoppedAt}}, lastSeen}
  config: {},          // merged over DEFAULT_CONFIG
  deliveries: {},      // webhook delivery-id dedupe
  meta: { createdAt: Date.now() }
};

function loadDb() {
  try {
    if (fs.existsSync(DB_PATH)) {
      const raw = JSON.parse(fs.readFileSync(DB_PATH, 'utf8'));
      ['users', 'sessions', 'payments', 'imei', 'deliveries'].forEach(k => { if (raw[k]) db[k] = raw[k]; });
      db.config = raw.config || {};
      db.meta = raw.meta || db.meta;
      console.log('[db] loaded', db.users.length, 'users,', db.payments.length, 'payments');
    }
  } catch (e) { console.error('[db] load failed:', e.message); }
}
let saveTimer = null;
function saveDb() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      fs.mkdirSync(DATA_DIR, { recursive: true });
      const tmp = DB_PATH + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(db));
      fs.renameSync(tmp, DB_PATH);
    } catch (e) { console.error('[db] save failed:', e.message); }
  }, 250);
}
loadDb();

function cfg() {
  const c = JSON.parse(JSON.stringify(DEFAULT_CONFIG));
  deepMerge(c, db.config || {});
  // env overrides (highest priority)
  if (process.env.SWIFTWALLET_API_KEY) c.swiftwallet.apiKey = process.env.SWIFTWALLET_API_KEY;
  if (process.env.SWIFTWALLET_WEBHOOK_SECRET) c.swiftwallet.webhookSecret = process.env.SWIFTWALLET_WEBHOOK_SECRET;
  return c;
}
function deepMerge(dst, src) {
  for (const k of Object.keys(src || {})) {
    if (src[k] && typeof src[k] === 'object' && !Array.isArray(src[k]) && dst[k] && typeof dst[k] === 'object' && !Array.isArray(dst[k])) deepMerge(dst[k], src[k]);
    else dst[k] = src[k];
  }
}

/* ------------------------- helpers ------------------------- */
const now = () => Date.now();
const rid = (p) => p + '_' + crypto.randomBytes(9).toString('hex');
const todayStr = () => new Date().toISOString().slice(0, 10);
function sha(s) { return crypto.createHash('sha256').update(s).digest('hex'); }
function hashPin(pin, salt) { return sha(salt + '::' + pin + '::fridah'); }

function normPhone(p) {
  p = String(p || '').replace(/\s+/g, '');
  if (/^\+?254(1|7)\d{8}$/.test(p)) p = '0' + p.replace(/^\+?254/, '');
  return p;
}
function validPhone(p) { return /^0(1|7)\d{8}$/.test(p); }

function findUserByPhone(phone) { return db.users.find(u => u.phone === phone) || null; }
function userById(id) { return db.users.find(u => u.id === id) || null; }

function activePackage(user) {
  const ap = user && user.activePackage;
  if (!ap || !ap.expiresAt) return null;
  if (ap.expiresAt < now()) return null;
  return ap;
}

function publicUser(u) {
  const ap = activePackage(u);
  const freenet = cfg().freenet;
  const days = Object.keys(u.freeNetDays || {}).length;
  return {
    id: u.id, phone: u.phone, imei: u.imei, device: u.device || '',
    createdAt: u.createdAt, lastSeen: u.lastSeen,
    freeNet: { daysUsed: days, daysTotal: freenet.days, usedToday: !!(u.freeNetDays && u.freeNetDays[todayStr()]) },
    activePackage: ap ? { packageId: ap.packageId, name: ap.name, hours: ap.hours, price: ap.price, expiresAt: ap.expiresAt } : null
  };
}

/* ------------------------- live streams (SSE) ------------------------- */
const payStreams = new Map();   // paymentId -> Set<res>
const adminStreams = new Set();
function sseInit(res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache',
    'Connection': 'keep-alive', 'X-Accel-Buffering': 'no'
  });
  res.write(':ok\n\n');
}
function sseSend(res, ev, obj) {
  try { res.write('event: ' + ev + '\ndata: ' + JSON.stringify(obj) + '\n\n'); } catch (e) {}
}
function heartbeat(setish) {
  for (const r of setish) { try { r.write(': hb ' + now() + '\n\n'); } catch (e) { try { r.end(); } catch (e2) {} } }
}
setInterval(() => { heartbeat(payStreams.get('*') || new Set()); heartbeat(adminStreams); }, 15000);

function broadcastPayment(p, user) {
  const evt = { paymentId: p.id, status: p.status, packageId: p.packageId, amount: p.amount, receipt: p.receipt || '', user: user ? { id: user.id, phone: user.phone } : null, activePackage: user ? activePackage(user) : null };
  const set = payStreams.get(p.id);
  if (set) for (const r of set) sseSend(r, 'payment', evt);
  const star = payStreams.get('*');
  if (star) for (const r of star) sseSend(r, 'payment', evt);
  for (const r of adminStreams) sseSend(r, 'payment', adminPaymentView(p, user));
}
setInterval(() => { // prune dead payment streams
  for (const [k, set] of payStreams) { for (const r of [...set]) { if (r.writableEnded) set.delete(r); } if (k !== '*' && set.size === 0) payStreams.delete(k); }
  for (const r of [...adminStreams]) if (r.writableEnded) adminStreams.delete(r);
}, 30000);

function adminPaymentView(p, user) {
  return { id: p.id, phone: user ? user.phone : p.phone, packageId: p.packageId, amount: p.amount, status: p.status, receipt: p.receipt || '', createdAt: p.createdAt, completedAt: p.completedAt || null, reference: p.reference };
}

/* ------------------------- swiftwallet ------------------------- */
function swRequest(pathName, body, apiKey) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(JSON.stringify(body));
    const u = new URL(SW_BASE + pathName);
    const mod = u.protocol === 'https:' ? require('https') : require('http');
    const req = mod.request({
      hostname: u.hostname, port: u.port || 443, path: u.pathname + (u.search || ''), method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + apiKey, 'Accept': 'application/json',
        'Content-Type': 'application/json', 'Content-Length': data.length
      }
    }, res => {
      let chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        const raw = Buffer.concat(chunks).toString('utf8');
        let j = null; try { j = JSON.parse(raw); } catch (e) {}
        resolve({ http: res.statusCode, body: j, raw });
      });
    });
    req.on('error', reject);
    req.setTimeout(20000, () => { req.destroy(new Error('swiftwallet timeout')); });
    req.write(data); req.end();
  });
}

/* ------------------------- github update feed ------------------------- */
let ghCache = { at: 0, val: null };
function fetchUpdateFromGithub(repo) {
  return new Promise(resolve => {
    if (!repo) return resolve(null);
    if (ghCache.val && now() - ghCache.at < 300000) return resolve(ghCache.val);
    const req = https.get({ hostname: 'api.github.com', path: '/repos/' + repo + '/releases/latest', headers: { 'User-Agent': 'fridah-backend', 'Accept': 'application/vnd.github+json' } }, res => {
      let chunks = []; res.on('data', c => chunks.push(c));
      res.on('end', () => {
        try {
          const j = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (!j || !j.tag_name) { ghCache = { at: now(), val: null }; return resolve(null); }
          const m = String(j.tag_name).match(/v?([\d.]+)\+?(\d+)?/i);
          const apk = (j.assets || []).find(a => /\.apk$/i.test(a.name));
          const val = {
            name: m ? m[1] : String(j.tag_name),
            code: m && m[2] ? parseInt(m[2], 10) : 0,
            apkUrl: apk ? apk.browser_download_url : ((j.assets || [])[0] || {}).browser_download_url || ''
          };
          ghCache = { at: now(), val };
          resolve(val);
        } catch (e) { resolve(null); }
      });
    });
    req.on('error', () => resolve(null));
    req.setTimeout(8000, () => { req.destroy(); resolve(null); });
  });
}

/* ------------------------- app ------------------------- */
const app = express();
app.use(express.json({ limit: '256kb' }));
app.use((req, res, next) => { res.setHeader('X-Powered-By', 'fridah'); next(); });

// light rate limit
const rl = new Map();
function rateLimit(max) {
  return (req, res, next) => {
    const k = (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '') + '';
    const t = Math.floor(now() / 60000);
    let e = rl.get(k); if (!e || e.t !== t) { e = { t, c: 0 }; rl.set(k, e); }
    e.c++;
    if (e.c > max) return res.status(429).json({ error: 'Too many requests, slow down' });
    next();
  };
}

function auth(req, res, next) {
  const h = req.headers.authorization || '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : (req.query.token || '');
  const s = token && db.sessions[token];
  if (!s) return res.status(401).json({ error: 'Not logged in' });
  const u = userById(s.userId);
  if (!u) return res.status(401).json({ error: 'Account missing' });
  if (now() - s.createdAt > 30 * 86400000) { delete db.sessions[token]; saveDb(); return res.status(401).json({ error: 'Session expired' }); }
  req.user = u; req.token = token;
  next();
}

/* ================= public API ================= */
app.get('/healthz', (req, res) => res.json({ ok: true, time: now() }));
app.get('/', (req, res) => res.redirect('/admin'));

/* Webhook MUST be registered before the global JSON parser so the raw body
   stays a Buffer for HMAC signature verification. */
app.post('/api/webhook/swiftwallet', express.raw({ type: '*/*', limit: '512kb' }), (req, res) => {
  const c = cfg();
  const raw = Buffer.isBuffer(req.body) ? req.body : Buffer.from(typeof req.body === 'string' ? req.body : JSON.stringify(req.body || {}));
  const sig = String(req.headers['x-swiftwallet-signature'] || '').toLowerCase();
  const secret = c.swiftwallet.webhookSecret;
  if (secret) {
    const expected = crypto.createHmac('sha256', secret).update(raw).digest('hex');
    const a = Buffer.from(expected), b = Buffer.from(sig);
    if (!sig || a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
      console.warn('[webhook] bad signature; expected len', a.length, 'got len', b.length);
      return res.status(401).json({ error: 'Bad signature' });
    }
  }
  const delivery = String(req.headers['x-swiftwallet-delivery'] || '');
  if (delivery && db.deliveries[delivery]) return res.json({ ok: true, dedupe: true });
  if (delivery) { db.deliveries[delivery] = now(); if (Object.keys(db.deliveries).length > 5000) db.deliveries = {}; saveDb(); }

  let body = null; try { body = JSON.parse(raw.toString('utf8')); } catch (e) { return res.status(400).json({ error: 'Bad JSON' }); }
  const event = String(req.headers['x-swiftwallet-event'] || body.event || '').toLowerCase();
  const result = body.result || {};
  const ref = body.external_reference || '';
  let p = db.payments.find(x => x.reference === ref)
       || db.payments.find(x => x.sw && body.checkout_request_id && x.sw.checkout_request_id === body.checkout_request_id)
       || db.payments.find(x => x.sw && body.transaction_id && String(x.sw.transaction_id) === String(body.transaction_id));
  if (!p) { console.warn('[webhook] no payment matched, ref=', ref, 'event=', event); return res.json({ ok: true, matched: false }); }

  const okEvent = event.includes('completed') || (body.status && String(body.status).toLowerCase() === 'completed');
  const failEvent = event.includes('failed') || event.includes('cancelled') || event.includes('reversed')
    || ['failed', 'cancelled', 'reversed'].includes(String(body.status || '').toLowerCase());
  const u = userById(p.userId);
  if (okEvent && p.status !== 'completed') {
    p.status = 'completed'; p.completedAt = now();
    p.receipt = result.MpesaReceiptNumber || result.TransactionReceipt || body.reference || '';
    const pkg = cfg().packages.find(x => x.id === p.packageId) || { name: 'Package', hours: 1, price: p.amount };
    if (u) {
      u.activePackage = { packageId: pkg.id, name: pkg.name, hours: pkg.hours, price: pkg.price, startedAt: now(), expiresAt: now() + pkg.hours * 3600000 };
      saveDb();
      broadcastPayment(p, u);
    }
  } else if (failEvent && p.status === 'pending') {
    p.status = 'failed'; p.error = result.ResultDesc || body.status || 'Payment failed';
    saveDb(); broadcastPayment(p, u);
  } else { saveDb(); broadcastPayment(p, u); }
  res.json({ ok: true });
});

app.get('/api/config', rateLimit(240), async (req, res) => {
  const c = cfg();
  let update = null;
  if (c.update && c.update.code && c.update.apkUrl) {
    update = { code: c.update.code, name: c.update.name || '', apkUrl: c.update.apkUrl };
  } else {
    const gh = await fetchUpdateFromGithub(c.githubRepo);
    if (gh && gh.code && gh.apkUrl) update = gh;
    else if (c.update && c.update.apkUrl) update = { code: c.update.code || 0, name: c.update.name || '', apkUrl: c.update.apkUrl };
  }
  res.json({
    maintenance: c.maintenance,
    freenet: c.freenet,
    packages: c.packages,
    reels: c.reels,
    messages: c.messages,
    welcome: { title: c.messages.welcomeTitle, body: c.messages.welcomeBody, phone: c.messages.adminPhone },
    update: update || { code: 0, name: '', apkUrl: '' },
    serverTime: now()
  });
});

app.post('/api/auth/register', rateLimit(40), (req, res) => {
  const phone = normPhone(req.body.phone);
  const pin = String(req.body.pin || '');
  const imei = String(req.body.imei || '').slice(0, 40);
  const device = String(req.body.device || '').slice(0, 80);
  if (!validPhone(phone)) return res.status(400).json({ error: 'Enter a valid Safaricom/Airtel number (07xx or 01xx)' });
  if (!/^\d{4}$/.test(pin)) return res.status(400).json({ error: 'PIN must be exactly 4 numbers' });
  if (!imei) return res.status(400).json({ error: 'Device ID missing' });
  if (findUserByPhone(phone)) return res.status(409).json({ error: 'Phone already registered. Please login.' });
  const salt = crypto.randomBytes(8).toString('hex');
  const u = {
    id: rid('u'), phone, pinHash: hashPin(pin, salt), salt, imei, device,
    createdAt: now(), lastSeen: now(), freeNetDays: {}, activePackage: null
  };
  db.users.push(u);
  const token = crypto.randomBytes(24).toString('hex');
  db.sessions[token] = { userId: u.id, createdAt: now() };
  saveDb();
  res.json({ ok: true, token, user: publicUser(u) });
});

app.post('/api/auth/login', rateLimit(40), (req, res) => {
  const phone = normPhone(req.body.phone);
  const pin = String(req.body.pin || '');
  const imei = String(req.body.imei || '').slice(0, 40);
  const device = String(req.body.device || '').slice(0, 80);
  const u = findUserByPhone(phone);
  if (!u) return res.status(401).json({ error: 'Account not found. Register first.' });
  if (hashPin(pin, u.salt) !== u.pinHash) return res.status(401).json({ error: 'Wrong phone or PIN' });
  u.lastSeen = now();
  if (imei) u.imei = imei;
  if (device) u.device = device;
  const token = crypto.randomBytes(24).toString('hex');
  db.sessions[token] = { userId: u.id, createdAt: now() };
  saveDb();
  res.json({ ok: true, token, user: publicUser(u) });
});

app.get('/api/me', auth, (req, res) => {
  req.user.lastSeen = now(); saveDb();
  res.json({ ok: true, user: publicUser(req.user) });
});

/* ---- free net (1 session/day, 20 min, 3 days, per IMEI) ---- */
app.post('/api/freenet/start', auth, rateLimit(60), (req, res) => {
  const imei = String(req.body.imei || req.user.imei || '').slice(0, 40);
  if (!imei) return res.status(400).json({ error: 'Device ID missing' });
  const c = cfg().freenet;
  const today = todayStr();
  const reg = db.imei[imei] || (db.imei[imei] = { days: {}, lastSeen: now() });
  reg.lastSeen = now();
  const dayCount = Object.keys(reg.days).length;
  if (reg.days[today]) {
    const d = reg.days[today];
    const elapsedMin = Math.floor((now() - d.startedAt) / 60000);
    const remaining = c.minutes - elapsedMin;
    if (!d.stoppedAt && remaining > 0) {
      // dropped connection mid-session: resume with the REMAINING minutes (still one session per day)
      return res.json({ allowed: true, minutes: remaining, mbCap: c.mbCap, day: dayCount, daysTotal: c.days, resumed: true });
    }
    return res.status(403).json({ allowed: false, reason: 'today', error: 'Free net already used today (1 session per day). Come back tomorrow or buy a package.' });
  }
  if (dayCount >= c.days) {
    return res.status(403).json({ allowed: false, reason: 'over', error: 'Free net is over. Buy net!' });
  }
  reg.days[today] = { startedAt: now(), minutes: c.minutes, mbCap: c.mbCap, stoppedAt: null };
  req.user.freeNetDays[today] = { imei, startedAt: now() };
  saveDb();
  res.json({
    allowed: true, minutes: c.minutes, mbCap: c.mbCap,
    day: dayCount + 1, daysTotal: c.days,
    message: 'Free net started — ' + c.minutes + ' minutes today (' + (dayCount + 1) + ' of ' + c.days + ' free days used)'
  });
});

app.post('/api/freenet/stop', auth, rateLimit(60), (req, res) => {
  const imei = String(req.body.imei || req.user.imei || '').slice(0, 40);
  const reg = db.imei[imei];
  if (reg) {
    const today = reg.days[todayStr()];
    if (today && !today.stoppedAt) today.stoppedAt = now();
  }
  saveDb();
  res.json({ ok: true });
});

/* ---- payments: STK push + LIVE confirmation ---- */
app.post('/api/pay/initiate', auth, rateLimit(30), async (req, res) => {
  const c = cfg();
  const pkg = c.packages.find(p => p.id === String(req.body.packageId || ''));
  if (!pkg) return res.status(400).json({ error: 'Unknown package' });
  if (!c.swiftwallet.apiKey) return res.status(503).json({ error: 'Payments not configured yet. Admin: set SwiftWallet API key in Settings.' });
  const phone = normPhone(req.body.phone || req.user.phone);
  if (!validPhone(phone)) return res.status(400).json({ error: 'Valid M-PESA phone needed (07xx/01xx)' });
  const reference = rid('pay').toUpperCase().replace('_PAY_', '-');
  const payment = {
    id: rid('pay'), reference, userId: req.user.id, phone, packageId: pkg.id,
    amount: pkg.price, status: 'pending', sw: null, createdAt: now(), completedAt: null, receipt: ''
  };
  try {
    const r = await swRequest('/v3/stk-initiate', {
      amount: Math.round(pkg.price),
      phone_number: phone,
      external_reference: reference,
      customer_name: req.user.device || ('Fridah ' + phone.slice(-4)),
      callback_url: PUBLIC_URL + '/api/webhook/swiftwallet'
    }, c.swiftwallet.apiKey);
    if (!r.body || r.body.success !== true) {
      payment.status = 'failed';
      payment.error = (r.body && (r.body.message || r.body.error)) || ('HTTP ' + r.http);
      db.payments.push(payment); saveDb();
      for (const rst of adminStreams) sseSend(rst, 'payment', adminPaymentView(payment, req.user));
      return res.status(502).json({ error: 'STK push failed: ' + payment.error });
    }
    payment.sw = { transaction_id: r.body.transaction_id, checkout_request_id: r.body.checkout_request_id, merchant_request_id: r.body.merchant_request_id, status: r.body.status };
    db.payments.push(payment); saveDb();
    for (const rst of adminStreams) sseSend(rst, 'payment', adminPaymentView(payment, req.user));
    res.json({ ok: true, paymentId: payment.id, status: payment.sw.status || 'INITIATED', amount: payment.amount, message: 'STK push sent to ' + phone + '. Enter your M-PESA PIN.' });
  } catch (e) {
    payment.status = 'failed'; payment.error = e.message;
    db.payments.push(payment); saveDb();
    res.status(502).json({ error: 'STK push error: ' + e.message });
  }
});

app.get('/api/pay/status/:id', auth, (req, res) => {
  const p = db.payments.find(x => x.id === req.params.id && x.userId === req.user.id);
  if (!p) return res.status(404).json({ error: 'Payment not found' });
  const u = userById(p.userId);
  res.json({ ok: true, paymentId: p.id, status: p.status, amount: p.amount, receipt: p.receipt || '', error: p.error || null, activePackage: u ? activePackage(u) : null });
});

// LIVE stream for a payment (EventSource can't set headers -> token via query allowed)
app.get('/api/pay/stream/:id', (req, res) => {
  const h = req.headers.authorization || '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : (req.query.token || '');
  const s = token && db.sessions[token];
  const u = s && userById(s.userId);
  if (!u) return res.status(401).end();
  const p = db.payments.find(x => x.id === req.params.id && x.userId === u.id);
  if (!p) return res.status(404).end();
  sseInit(res);
  sseSend(res, 'payment', { paymentId: p.id, status: p.status, receipt: p.receipt || '', activePackage: activePackage(u) });
  let set = payStreams.get(p.id); if (!set) { set = new Set(); payStreams.set(p.id, set); }
  set.add(res);
  req.on('close', () => { set.delete(res); });
});

/* ================= admin API ================= */
function adminAuth(req, res, next) {
  const c = cfg();
  const pass = process.env.ADMIN_PASSWORD || 'Fridah@2026';
  const key = req.headers['x-admin-key'] || req.query.key || '';
  const a = Buffer.from(String(key)), b = Buffer.from(pass);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return res.status(401).json({ error: 'Wrong admin password' });
  next();
}
app.post('/api/admin/login', rateLimit(30), (req, res) => {
  const pass = process.env.ADMIN_PASSWORD || 'Fridah@2026';
  const a = Buffer.from(String(req.body.password || '')), b = Buffer.from(pass);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return res.status(401).json({ error: 'Wrong admin password' });
  res.json({ ok: true });
});
app.get('/api/admin/stats', adminAuth, (req, res) => {
  const t0 = new Date(); t0.setHours(0, 0, 0, 0);
  const todays = db.payments.filter(p => p.status === 'completed' && p.completedAt >= t0.getTime());
  res.json({
    users: db.users.length,
    activePackages: db.users.filter(u => activePackage(u)).length,
    paymentsToday: todays.length,
    revenueToday: todays.reduce((s, p) => s + p.amount, 0),
    revenueTotal: db.payments.filter(p => p.status === 'completed').reduce((s, p) => s + p.amount, 0),
    freeNetToday: Object.keys(db.imei).filter(i => db.imei[i].days && db.imei[i].days[todayStr()]).length
  });
});
app.get('/api/admin/users', adminAuth, (req, res) => {
  res.json({
    users: db.users.map(u => ({
      ...publicUser(u),
      pinSet: true,
      spent: db.payments.filter(p => p.userId === u.id && p.status === 'completed').reduce((s, p) => s + p.amount, 0),
      payments: db.payments.filter(p => p.userId === u.id).length,
      freeNetDates: Object.keys(u.freeNetDays || {})
    })).sort((a, b) => b.createdAt - a.createdAt)
  });
});
app.get('/api/admin/payments', adminAuth, (req, res) => {
  const lim = Math.min(parseInt(req.query.limit || '200', 10), 500);
  const list = db.payments.slice().sort((a, b) => b.createdAt - a.createdAt).slice(0, lim)
    .map(p => adminPaymentView(p, userById(p.userId)));
  res.json({ payments: list });
});
app.get('/api/admin/stream', adminAuth, (req, res) => {
  sseInit(res); adminStreams.add(res);
  const recent = db.payments.slice().sort((a, b) => b.createdAt - a.createdAt).slice(0, 15)
    .map(p => adminPaymentView(p, userById(p.userId)));
  sseSend(res, 'snapshot', { payments: recent });
  req.on('close', () => adminStreams.delete(res));
});
app.get('/api/admin/config', adminAuth, (req, res) => res.json({ config: cfg() }));
app.post('/api/admin/config', adminAuth, (req, res) => {
  const allowed = ['maintenance', 'freenet', 'packages', 'reels', 'messages', 'swiftwallet', 'githubRepo', 'update', 'welcome'];
  db.config = db.config || {};
  deepMerge(db.config, req.body || {});
  saveDb();
  res.json({ ok: true, config: cfg() });
});
app.post('/api/admin/maintenance', adminAuth, (req, res) => {
  db.config = db.config || {};
  db.config.maintenance = { on: !!req.body.on, message: String(req.body.message || DEFAULT_CONFIG.maintenance.message) };
  saveDb();
  res.json({ ok: true, maintenance: db.config.maintenance });
});
app.get('/api/admin/export.csv', adminAuth, (req, res) => {
  const rows = ['phone,imei,device,joined,lastSeen,freeNetDays,activePackage,spent,payments'];
  for (const u of db.users) {
    const ap = activePackage(u);
    rows.push([u.phone, u.imei, (u.device || '').replace(/,/g, ' '), new Date(u.createdAt).toISOString(), new Date(u.lastSeen).toISOString(),
      Object.keys(u.freeNetDays || {}).length, ap ? ap.name + ' until ' + new Date(ap.expiresAt).toISOString() : '', db.payments.filter(p => p.userId === u.id && p.status === 'completed').reduce((s, p) => s + p.amount, 0), db.payments.filter(p => p.userId === u.id).length].join(','));
  }
  res.setHeader('Content-Type', 'text/csv');
  res.setHeader('Content-Disposition', 'attachment; filename=fridah-users.csv');
  res.send(rows.join('\n'));
});

/* admin site */
app.get('/admin', (req, res) => res.sendFile(path.join(__dirname, 'public', 'admin.html')));
app.use('/admin', express.static(path.join(__dirname, 'public')));

app.use((req, res) => res.status(404).json({ error: 'Not found' }));
app.use((err, req, res, next) => { console.error('[err]', err.message); res.status(500).json({ error: 'Server error' }); });

app.listen(PORT, () => console.log('Fridah backend live on :' + PORT + '  public=' + PUBLIC_URL));
